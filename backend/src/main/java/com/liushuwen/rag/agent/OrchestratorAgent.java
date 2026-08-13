package com.liushuwen.rag.agent;

import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.rag.CriticService;
import com.liushuwen.rag.rag.Critique;
import com.liushuwen.rag.rag.Route;
import com.liushuwen.rag.rag.RouterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 主管 Agent（阶段4 ✅ 已实现）
 *
 * 职责：路由判断 → 分派给对应专用 Agent → 回答质量反思（不合格重写一次）→ 汇总返回。
 * Spring 自动收集所有 Agent Bean（构造器注入 List<Agent>）。
 *
 * 面试考点：
 * - 多 Agent 相比单 Agent：每 Agent 决策空间小、上下文干净、Prompt 聚焦
 * - 反思成本控制：只在编排层做一次，且重写次数硬上限（criticMaxRetry）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrchestratorAgent {

    private final RouterService routerService;
    private final List<Agent> agents;
    /** 阶段4-反思：回答质量评审 + 重写 */
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
     * 多 Agent 编排入口
     *
     * @param question 用户问题
     * @param history  会话历史
     * @return 最终回答
     */
    public String execute(String question, List<Map<String, Object>> history) {
        Route route = routerService.route(question);
        String answer;
        switch (route) {
            case STATS -> {
                Agent statsAgent = findAgent(Agent.AgentType.STATS);
                answer = statsAgent.execute(question, history);
            }
            case HYBRID -> {
                // HYBRID = 先查数据统计，再结合文档问答（组合回答）
                Agent statsAgent = findAgent(Agent.AgentType.STATS);
                Agent docAgent = findAgent(Agent.AgentType.DOCUMENT);
                String stats = statsAgent.execute(question, history);
                String doc = docAgent.execute(question, history);
                answer = "【数据概况】\n" + stats + "\n\n【文档解答】\n" + doc;
            }
            default -> {
                Agent docAgent = findAgent(Agent.AgentType.DOCUMENT);
                answer = docAgent.execute(question, history);
            }
        }
        log.info("[Orchestrator] 路由={}，完成生成", route);

        // ---- 阶段4-反思：评审回答质量，不合格带意见重写一次（防无限循环）----
        return selfCorrect(question, answer);
    }

    /**
     * 反思与自我修正（TODO 4-3 ✅ 已实现）
     * 评审失败/解析失败默认放行；重写次数硬上限 criticMaxRetry（默认1）
     */
    private String selfCorrect(String question, String answer) {
        int maxRetry = ragProperties.getAgent().getCriticMaxRetry();
        String current = answer;
        for (int i = 0; i < maxRetry; i++) {
            Critique c = criticService.judge(question, current, List.of());
            if (c.isPass()) {
                return current;                     // 合格，直接返回
            }
            log.info("[Critic] 回答不合格（{}），第{}次重写: {}", c.getReason(), i + 1, question);
            // 带着评审意见重写（把 reason 塞进 system）
            current = llmService.chatWithSystem(
                    "根据评审意见改进你的回答，要求更直接地回答用户问题、逻辑清晰。\n评审意见：" + c.getReason(),
                    question, 0.4);
        }
        return current;
    }
}
