package com.liushuwen.rag.agent;

import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.rag.CriticService;
import com.liushuwen.rag.rag.Critique;
import com.liushuwen.rag.rag.Route;
import com.liushuwen.rag.rag.RouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 主管 Agent：路由意图后分派专用子 Agent，再经 CriticService 反思评审（不合格重写一次）汇总返回。
 * 【设计要点】多 Agent 主管模式（Supervisor）：意图路由 + 子 Agent 分派 + HYBRID 组合回答
 * 【常见问题】反思为何只做一次且重写上限硬控？——控制 LLM 成本与延迟，防自我修正无限循环
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorAgent {

    private final RouterService routerService;
    private final List<Agent> agents;
    /** 回答质量评审 + 重写（CriticService） */
    private final CriticService criticService;
    private final LlmService llmService;
    private final com.liushuwen.rag.config.RagProperties ragProperties;

    /** 按类型取专用 Agent，找不到降级给 DOCUMENT */
    private Agent findAgent(Agent.AgentType type) {
        return agents.stream()
                .filter(a -> a.type() == type)
                .findFirst()
                .orElseGet(() -> agents.stream()
                        .filter(a -> a.type() == Agent.AgentType.DOCUMENT)
                        .findFirst()
                        .orElseThrow());
    }

    /**
     * 多 Agent 编排入口（只取回答文本）。
     * 【设计要点】保留此签名供 AgentController 等"只要文本"的调用方使用；需要落库带来源时用 executeResult
     *
     * @param question 用户问题
     * @param history  会话历史
     * @return 最终回答
     */
    public String execute(String question, List<Map<String, Object>> history) {
        return executeResult(question, history).answer();
    }

    /**
     * 多 Agent 编排入口（回答 + 依据）。
     * 【设计要点】对话链路需要把"依据片段"一并落库随回答返回，故不能只给文本——
     * 这也是把编排接进对话页时必须补的能力：原来只返回 String，落库只能存空 sources，
     * 前端「参考来源」区就是空的，等于把 agent 的能力阉割掉一半。
     *
     * @param question 用户问题
     * @param history  会话历史（多轮上下文，供子 Agent 注入 Prompt）
     * @return AgentResult（answer 非空；evidence 为检索到的文档片段或工具原文，可能为空，表示无依据）
     */
    public AgentResult executeResult(String question, List<Map<String, Object>> history) {
        Route route = routerService.route(question);
        AgentResult result;
        // 是否为"工具直答"：输出不经过 LLM，是数据库聚合的确定性事实
        boolean toolGrounded;
        switch (route) {
            case STATS -> {
                Agent statsAgent = findAgent(Agent.AgentType.STATS);
                result = statsAgent.execute(question, history);
                toolGrounded = true;
            }
            case HYBRID -> {
                // HYBRID = 先查数据统计，再结合文档问答（组合回答）
                Agent statsAgent = findAgent(Agent.AgentType.STATS);
                Agent docAgent = findAgent(Agent.AgentType.DOCUMENT);
                AgentResult stats = statsAgent.execute(question, history);
                AgentResult doc = docAgent.execute(question, history);
                result = AgentResult.of(
                        "【数据概况】\n" + stats.answer() + "\n\n【文档解答】\n" + doc.answer(),
                        concat(stats.evidence(), doc.evidence()));
                toolGrounded = false;      // 组合回答含 LLM 生成部分，仍需反思
            }
            default -> {
                Agent docAgent = findAgent(Agent.AgentType.DOCUMENT);
                result = docAgent.execute(question, history);
                toolGrounded = false;
            }
        }
        log.info("[Orchestrator] 路由={}，完成生成", route);

        // 功能：反思评审，不合格带意见重写一次（重写次数硬上限，防无限循环）｜要点：Critic 自省
        // 【缺陷修复·反思误用】STATS 分支的回答由工具直出（MySQL 聚合事实），既不存在需要纠正的幻觉，
        // 也没有"文档依据片段"可供评委核对。修复前对分支统一调 Critic 并传空证据（List.of()），
        // 评委必判"无知识库依据"不合格 → 触发一次纯 LLM 重写，把准确数字换成模型的模糊复述
        // （验收实测：回答退化为"根据当前可检索到的知识库内容，文档数量为"，数字与列表全丢）。
        // 结论：确定性事实不进"评审-重写"闭环，反思只作用于 LLM 生成的回答。
        if (toolGrounded) {
            log.info("[Orchestrator] 路由={} 为工具直答，跳过反思评审（重写只会降质）", route);
            return result;
        }
        return selfCorrect(question, result);
    }

    /** 拼接两组证据片段（供 HYBRID 组合回答评审用） */
    private List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a == null ? List.of() : a);
        if (b != null) {
            all.addAll(b);
        }
        return all;
    }

    /**
     * 反思与自我修正：评审回答质量，不合格则带评审意见重写，次数硬上限 criticMaxRetry（默认1）。
     * 【设计要点】Critic/自省机制：用独立 LLM 评审主回答，闭环提升质量而非一次性生成
     * 【常见问题】评审失败为何默认放行？——避免评审链路异常阻断主流程，保证可用性优先
     * 【设计要点】重写只改 answer，evidence 原样透传：依据是"检索到的事实"，不因措辞重写而改变
     */
    private AgentResult selfCorrect(String question, AgentResult result) {
        int maxRetry = ragProperties.getAgent().getCriticMaxRetry();
        String current = result.answer();
        for (int i = 0; i < maxRetry; i++) {
            // 功能：把生成回答所用的证据片段一并交给评委｜要点：评委标准含"是否有知识库依据"，
            // 传空证据会让任何回答都必判不合格（反思形同虚设还多烧一次 LLM）
            Critique c = criticService.judge(question, current, result.evidence());
            if (c.isPass()) {
                return AgentResult.of(current, result.evidence());   // 合格
            }
            log.info("[Critic] 回答不合格（{}），第{}次重写: {}", c.getReason(), i + 1, question);
            // 带着评审意见重写（把 reason 塞进 system）
            String rewritten = llmService.chatWithSystem(
                    "根据评审意见改进你的回答，要求更直接地回答用户问题、逻辑清晰。\n评审意见：" + c.getReason(),
                    question, 0.4);
            // 【缺陷修复·空回答】重写调用可能返回空内容（如思考模型只回 reasoning_content、或返回体异常）。
            // 若直接采用空串，用户会收到 HTTP 200 + 空回答——比"未重写"更糟。故空值即保留原回答，保证可用性优先。
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("[Critic] 第{}次重写返回空内容，保留原回答", i + 1);
                return AgentResult.of(current, result.evidence());
            }
            current = rewritten;
        }
        return AgentResult.of(current, result.evidence());
    }
}
