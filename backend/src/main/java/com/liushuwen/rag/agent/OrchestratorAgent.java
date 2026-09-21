package com.liushuwen.rag.agent;

import com.liushuwen.rag.llm.LlmService;
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
 * 【设计要点】四条分派分支：DOCUMENT（RAG 问答）/ STATS（工具直答）/ REPORT（ReAct + 报告生成）/ HYBRID（组合），
 * 由 RouterService 的 LLM 分类产出；子 Agent 通过 Spring 注入的 List&lt;Agent&gt; 按 type() 取用（策略模式）。
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
     * 多 Agent 编排入口（回答 + 依据）。
     * 【设计要点】对话链路需要把"依据片段"一并落库随回答返回，故不能只给文本——
     * 这也是把编排接进对话页时必须补的能力：原来只返回 String，落库只能存空 sources，
     * 前端「参考来源」区就是空的，等于把 agent 的能力阉割掉一半。
     * 【设计要点·为什么不再提供"只取文本"的重载】此前另有一个 execute(question, history) 返回 String 的重载，
     * 唯一调用方是已删除的编排接口 POST /api/agent/orchestrate。该端点与产品入口（对话页 mode=agent =
     * 同一套编排 + 多轮历史 + 落库）完全重叠、前端引用为 0，属纯冗余，故端点与重载一并移除。
     *
     * @param question 用户问题
     * @param history  会话历史（多轮上下文，供子 Agent 注入 Prompt）
     * @return AgentResult（answer 非空；evidence 为检索到的文档片段或工具原文，可能为空，表示无依据）
     */
    public AgentResult executeResult(String question, List<Map<String, Object>> history) {
        Route route = routerService.route(question);
        AgentResult result;
        // 是否跳过反思评审（重写只会帮倒忙的三种情况）：
        //   ① STATS —— 回答由工具直出（MySQL 聚合的确定性事实），既没有幻觉可纠，
        //      也没有"文档依据片段"供评委核对；
        //   ② REPORT —— 回答是长结构化产物，而重写 Prompt（"更直接地回答用户问题、逻辑清晰"）
        //      是面向短问答设计的，套在整篇报告上会把章节结构推平；报告的依据同样已随
        //      ReAct 的工具输出返回（AgentResult.evidence），不靠重写补质量；
        //   ③ HYBRID —— 组合回答的两段拼接而成，其中【数据概况】段与 STATS 同源（工具直出的
        //      确定性数字），重写同样会把它换成模糊复述；且两段来源天然"看似矛盾"，
        //      评委几乎必判不合格 ⇒ 重写必然发生、必然抹平结构（09-20 实测 200+ 字 → 54 字）。
        boolean skipReflection;
        switch (route) {
            case STATS -> {
                Agent statsAgent = findAgent(Agent.AgentType.STATS);
                result = statsAgent.execute(question, history);
                skipReflection = true;
            }
            case REPORT -> {
                // 报告生成：走 ReportAgent（内部即 AgentExecutor 的 ReAct 循环 + generate_report 工具）
                Agent reportAgent = findAgent(Agent.AgentType.REPORT);
                result = reportAgent.execute(question, history);
                skipReflection = true;
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
                // 【缺陷修复·HYBRID 漏在反思之外】原为 skipReflection = false（"组合回答含 LLM 生成部分，仍需反思"），
                // 但这条判断漏了 HYBRID 与 STATS 同源的那一半：组合回答里【数据概况】段是**工具直出的确定性数字**，
                // 反思重写（Prompt 面向短问答："更直接地回答用户问题、逻辑清晰"）同样会把它换成模型的模糊复述——
                // 09-20 实测确证：路由=HYBRID 时 Critic 判定"对文档数量的回答自相矛盾…整体回答不够简洁"并触发重写，
                // 返回的正文从「【数据概况】+ 完整统计与文档列表 +【文档解答】」被压缩成一句 54 字的口语概述，
                // 两个结构标签与文档列表全部丢失（结构是硬拼接的，重写必然抹平）。
                // 且两段来源天然"看似矛盾"（数字段说有 1 篇文档，检索段对同一问法答"资料未提及"），
                // 评委几乎必判不合格 ⇒ 重写几乎必然发生。故与 STATS/REPORT 同处理：跳过反思。
                skipReflection = true;
            }
            default -> {
                Agent docAgent = findAgent(Agent.AgentType.DOCUMENT);
                result = docAgent.execute(question, history);
                skipReflection = false;
            }
        }
        log.info("[Orchestrator] 路由={}，完成生成", route);

        // 功能：反思评审，不合格带意见重写一次（重写次数硬上限，防无限循环）｜要点：Critic 自省
        // 【缺陷修复·反思误用】修复前对 STATS 分支统一调 Critic 并传空证据（List.of()），
        // 评委必判"无知识库依据"不合格 → 触发一次纯 LLM 重写，把准确数字换成模型的模糊复述
        // （验收实测：回答退化为"根据当前可检索到的知识库内容，文档数量为"，数字与列表全丢）。
        // 结论：反思只作用于"面向短问答、由 LLM 自由生成"的回答（仅 DOCUMENT 一条分支）。
        // 09-20 后 STATS / REPORT / HYBRID 三条分支均已跳过（原因见上方 skipReflection 注释）。
        if (skipReflection) {
            log.info("[Orchestrator] 路由={} 为工具直答或长结构化产物，跳过反思评审（重写只会降质）", route);
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
            // 【缺陷修复·重写异常】重写本身也是一次 LLM 调用，熔断打开或调用失败时会抛异常。
            // 原实现让异常直接冒泡 ⇒ 一次"锦上添花"的重写失败会把已经生成好的回答整条打掉（用户拿到 500）。
            // 重写是增强而非必需，失败即保留原回答（与下方"空回答保留原文"同一原则）。
            String rewritten;
            try {
                rewritten = llmService.chatWithSystem(
                        "根据评审意见改进你的回答，要求更直接地回答用户问题、逻辑清晰。\n评审意见：" + c.getReason(),
                        question, 0.4);
            } catch (Exception e) {
                log.warn("[Critic] 第{}次重写调用失败，保留原回答: {}", i + 1, e.getMessage());
                return AgentResult.of(current, result.evidence());
            }
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
