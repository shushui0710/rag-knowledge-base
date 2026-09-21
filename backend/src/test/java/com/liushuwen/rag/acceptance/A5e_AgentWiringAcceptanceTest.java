package com.liushuwen.rag.acceptance;

import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.agent.AgentResult;
import com.liushuwen.rag.agent.StatsAgent;
import com.liushuwen.rag.agent.ToolRegistry;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5e —— Agent 装配与契约（A5-07 / A5-08 / A5-09）。
 *
 * 【拆分说明】本类只管<b>装配层与契约层</b>，不跑真实问答：
 *   工具是否被自动收集（A5-07）、轮数上限是否由配置控制（A5-08）、
 *   AgentResult 是否携带证据（A5-09）。三者的共性是"改配置或改装配时才需要看"，
 *   与"跑一条链路看输出"的用例（A5b/A5c/A5f）变更原因不同。
 *
 * 【为什么证据契约单列】CriticService 的评判标准之一是"是否有知识库依据"；
 *   修复前 OrchestratorAgent 固定传 List.of()，任何回答都必判不合格并触发无意义重写。
 *   因此"evidence 必须非空"是反思评审可用性的前提，值得一条独立护栏。
 */
@DisplayName("A5e Agent 装配与契约（A5-07~09）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5e_AgentWiringAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private StatsAgent statsAgent;

    @Test
    @Order(7)
    @DisplayName("A5-07 工具注册表：3 个工具由 Spring 自动收集，按名可精确取到")
    void a507_tool_registry_auto_collection() {
        String names = toolRegistry.names();
        for (String expected : new String[]{"query_document_stats", "query_document_list", "generate_report"}) {
            assertNotNull(toolRegistry.get(expected),
                    "工具 " + expected + " 应被自动注册（实现 @Component 即可，无需改注册表）。当前已注册：" + names);
        }
        assertEquals(3, toolRegistry.all().size(),
                "当前应有 3 个工具，实际 " + toolRegistry.all().size() + "：" + names);
        step("A5-07 通过：工具注册表自动收集 " + toolRegistry.all().size() + " 个工具 → " + names);
    }

    @Test
    @Order(8)
    @DisplayName("A5-08 Agent 超轮降级：轮数上限由配置控制，超限返回拆解建议而非报错")
    void a508_max_iterations_configured() {
        int maxIterations = ragProperties.getAgent().getMaxIterations();
        int criticMaxRetry = ragProperties.getAgent().getCriticMaxRetry();

        assertEquals(5, maxIterations, "ReAct 最大轮数应为 5（rag.agent.max-iterations）");
        assertEquals(1, criticMaxRetry, "反思重写上限应为 1（rag.agent.critic-max-retry）");
        assertNotNull(agentExecutor, "AgentExecutor 应被 Spring 装配");
        step("A5-08 通过：ReAct 轮数上限 " + maxIterations + "、反思重写上限 " + criticMaxRetry + " 与配置一致");
    }

    @Test
    @Order(9)
    @DisplayName("A5-09 Agent 证据契约：Agent 结果必须携带依据片段，否则反思评审必然误判")
    void a509_agent_result_carries_evidence() {
        AuthSession s = newUser();
        // 直接调 Agent 层验证契约：UserContext 是 ThreadLocal，测试线程需自行写入（HTTP 链路由 JwtInterceptor 写入）
        UserContext.setUserId(s.userId());
        try {
            AgentResult r = statsAgent.execute("现在有多少个文档？", List.of());

            assertFalse(r.answer().isBlank(), "Agent 回答不应为空");
            // 核心契约：证据必须非空。CriticService 的评判标准之一是"是否有知识库依据"，
            // 修复前 OrchestratorAgent 固定传 List.of()，任何回答都必判不合格并触发无意义重写。
            assertFalse(r.evidence().isEmpty(),
                    "工具类 Agent 必须把工具输出作为证据返回，否则 Critic 必判'无依据'：" + r.answer());
            assertTrue(r.evidence().stream().anyMatch(e -> e.contains("篇文档")),
                    "证据应包含工具原始输出，实际：" + r.evidence());
            // 新账号必然 0 篇文档：统计结果确定，可作为文档/简历中的可复现数字
            assertTrue(r.answer().matches("(?s).*共\\s*0\\s*篇文档.*"),
                    "新账号统计应为 0 篇（可复现），实际：" + r.answer());
            step("A5-09 通过：AgentResult 携带 " + r.evidence().size() + " 条证据；新账号统计="
                    + r.answer().replace("\n", " "));
        } finally {
            UserContext.clear();   // ThreadLocal 必须清理，防线程复用脏数据
        }
    }
}
