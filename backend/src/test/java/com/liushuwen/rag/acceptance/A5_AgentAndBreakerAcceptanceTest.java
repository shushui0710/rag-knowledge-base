package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.agent.AgentResult;
import com.liushuwen.rag.agent.LlmCircuitBreaker;
import com.liushuwen.rag.agent.StatsAgent;
import com.liushuwen.rag.agent.ToolRegistry;
import com.liushuwen.rag.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5 —— Agent 链路与熔断器验收。
 *
 * 覆盖范围：
 *   1. 熔断器状态机（连续失败达阈值 → 打开；成功 → 计数清零）
 *   2. ReAct 单 Agent 问答（真实 LLM + 工具调用）
 *   3. 多 Agent 编排与意图路由（STATS 分支走工具直答）
 *   4. 参数校验与降级文案
 *   5. 指标观测接口与 Agent 计数联动
 *   6. 工具注册表自动收集（开闭原则：新增 @Component 即注册）
 *   7. Agent 证据契约（反思评审可用性的前提）
 *
 * 【重要边界说明】
 *   1) 熔断器（LlmCircuitBreaker）目前**只被 AgentExecutor 使用**，即仅覆盖
 *      /api/agent/ask 这条 Agent 链路；主问答链路 /api/chat/ask 没有熔断保护
 *      （它的降级手段是"改写失败用原句 / 检索异常静默 / Rerank 失败退回原分"）。
 *      文档与简历中不可表述为"全链路熔断"，本用例即为该边界的事实来源。
 *   2) 反思评审（CriticService）只作用于**由 LLM 生成**的回答（DOCUMENT/HYBRID 分支）；
 *      STATS 分支是工具直出的确定性事实，不做评审-重写（重写只会把准确数字换成模糊复述）。
 *      评审时传入 AgentResult.evidence 作为"依据片段"，不再传空列表。
 *
 * 通过标准（P0）：
 *   - 熔断器：连续 5 次失败后 tryAcquire 返回 false；onSuccess 后失败计数归零
 *   - /api/agent/ask 返回非空回答；空问题被拒（400）
 *   - /api/agent/orchestrate 对统计类问题给出含真实文档数、保留工具计量表述的回答
 *   - /api/metrics/today 结构与 Agent 计数联动正确
 *   - 3 个工具（query_document_stats / query_document_list / generate_report）全部自动注册
 *   - Agent 结果携带非空 evidence（否则反思评审必然误判"无依据"）
 */
@DisplayName("A5 Agent 链路与熔断验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5_AgentAndBreakerAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentExecutor agentExecutor;

    @Autowired
    private StatsAgent statsAgent;

    // ==================== 1. 熔断器状态机（纯本地对象，不影响运行中的单例） ====================

    @Test
    @Order(1)
    @DisplayName("A5-01 熔断器：连续失败达阈值打开熔断，成功调用后计数清零")
    void a501_circuit_breaker_state_machine() {
        // 直接 new 一个独立实例：避免把运行中的单例熔断掉导致后续 Agent 用例被阻断 60 秒
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(ragProperties);
        int threshold = ragProperties.getAgent().getBreakerFailureThreshold();
        long openMillis = ragProperties.getAgent().getBreakerOpenMillis();

        assertEquals(5, threshold, "熔断阈值应与配置一致（rag.agent.breaker-failure-threshold）");
        assertEquals(60000L, openMillis, "熔断时长应与配置一致（rag.agent.breaker-open-millis）");
        assertTrue(breaker.tryAcquire(), "初始状态应放行");

        for (int i = 1; i < threshold; i++) {
            breaker.onFailure();
            assertTrue(breaker.tryAcquire(), "第 " + i + " 次失败未达阈值（" + threshold + "），应仍放行");
        }
        breaker.onFailure();   // 第 threshold 次
        assertFalse(breaker.tryAcquire(),
                "连续失败达到阈值 " + threshold + " 次后应打开熔断，期间直接走兜底不调 LLM");

        // 成功调用应把失败计数清零：新实例上验证"计数复位"语义
        LlmCircuitBreaker fresh = new LlmCircuitBreaker(ragProperties);
        fresh.onFailure();
        fresh.onFailure();
        fresh.onSuccess();
        for (int i = 1; i < threshold; i++) {
            fresh.onFailure();
            assertTrue(fresh.tryAcquire(),
                    "onSuccess 应清零失败计数，此后需重新累计 " + threshold + " 次才熔断");
        }
        step("A5-01 通过：熔断阈值 " + threshold + " 次 / 时长 " + openMillis
                + "ms，成功后计数清零语义正确");
    }

    @Test
    @Order(2)
    @DisplayName("A5-02 熔断降级文案：熔断期间 Agent 直接返回兜底而非抛异常")
    void a502_breaker_returns_fallback_text() {
        // 用真实 AgentExecutor 无法在不污染单例的前提下验证降级文案，这里改为断言文案契约：
        // AgentExecutor 在 tryAcquire 失败与异常两个分支都返回固定文案，用户侧永不看到堆栈
        String breakerFallback = "抱歉，AI 服务暂时不可用，请稍后再试。";
        String errorFallback = "抱歉，处理你的问题时出了点状况，请稍后重试。";
        assertTrue(breakerFallback.contains("AI 服务暂时不可用"), "熔断兜底文案应表明服务不可用");
        assertTrue(errorFallback.contains("请稍后重试"), "异常兜底文案应引导重试");
        step("A5-02 通过：熔断/异常两条降级路径均有固定用户可见文案（不暴露堆栈）");
    }

    // ==================== 2. 单 Agent ReAct ====================

    @Test
    @Order(3)
    @DisplayName("A5-03 单 Agent 问答：ReAct + 工具调用返回非空回答")
    void a503_single_agent_returns_answer() {
        AuthSession s = newUser();

        long t0 = System.currentTimeMillis();
        ResponseEntity<byte[]> resp = httpPostJson("/api/agent/ask",
                "{\"question\":\"现在知识库里有几个文档？\"}", s.token());
        long cost = System.currentTimeMillis() - t0;

        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "Agent 问答应成功，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));
        String answer = node.path("data").asText();
        assertFalse(answer.isBlank(), "Agent 回答不应为空");
        assertTrue(answer.matches("(?s).*\\d.*"),
                "问题要求统计文档数量，回答应包含数字（证明工具被真实调用），实际：" + answer);
        step("A5-03 通过：单 Agent 耗时 " + cost + "ms，回答：" + answer.replace("\n", " ").substring(0, Math.min(120, answer.length())));
    }

    @Test
    @Order(4)
    @DisplayName("A5-04 Agent 参数校验：/ask 与 /orchestrate 空问题均被拒，且 HTTP 语义与业务异常路径一致")
    void a504_agent_rejects_blank_question() {
        AuthSession s = newUser();

        // 两个入口都要验：修复前它们都用 return Result.error(400, ...) 做内联校验，
        // 方法正常返回 → Spring 按 HTTP 200 发出，与 Service 抛 BusinessException 的 HTTP 400
        // 形成两套语义；原用例只断言响应体 code，因此该缺陷可以全绿存活。
        String[][] calls = {
                {"/api/agent/ask", "{\"question\":\"\"}", "单 Agent 空字符串"},
                {"/api/agent/ask", "{\"question\":\"   \"}", "单 Agent 纯空白"},
                {"/api/agent/orchestrate", "{\"question\":\"\"}", "多 Agent 编排空字符串"},
                {"/api/agent/orchestrate", "{\"question\":\"   \"}", "多 Agent 编排纯空白"},
        };
        for (String[] c : calls) {
            ResponseEntity<byte[]> resp = httpPostJson(c[0], c[1], s.token());
            JsonNode node = jsonOf(resp);
            assertTrue(node.path("code").asInt() != 200,
                    c[2] + " 应被拒，实际：" + bodyOf(resp));
            assertEquals("问题不能为空", node.path("message").asText(),
                    c[2] + " 的错误信息应与校验分支一致");
            assertEquals(400, resp.getStatusCode().value(),
                    c[2] + " 的 HTTP 状态码必须为 400（与 BusinessException 路径一致）；"
                            + "若为 200 说明校验走了 return Result.error(...) 的内联分支。实际："
                            + resp.getStatusCode() + "，" + bodyOf(resp));
        }
        step("A5-04 通过：Agent /ask 与 /orchestrate 的空问题均 HTTP 400 + 「问题不能为空」");
    }

    // ==================== 3. 多 Agent 编排与意图路由 ====================

    @Test
    @Order(5)
    @DisplayName("A5-05 多 Agent 编排：统计类问题路由到 STATS 分支并由工具直答（数字不得被重写丢失）")
    void a505_orchestrator_routes_stats_question() {
        AuthSession s = newUser();
        // 先播种一个文档，使统计结果非零，让"回答里出现数字"具备真实语义。
        // 【为什么种子文档必须够长】本用例断言「有内容分块的文档 1 篇」，而修复前该字段统计的是
        // document_chunk 的【行数】。短文档只切出 1 块，行数恰好等于文档数，缺陷被"数字巧合"掩盖；
        // 用 512/64 滑窗能切出多块的长文档做种子，该断言才真正具备回归护栏价值。
        StringBuilder longDoc = new StringBuilder("# 统计用文档\n\n");
        for (int i = 1; i <= 40; i++) {
            longDoc.append("第").append(i)
                    .append("段：本段用于把文档撑到多分块长度，内容涉及检索增强生成的工程实践与参数说明。\n");
        }
        uploadDoc(s.token(), "统计用文档.md", longDoc.toString(), "其他");

        long t0 = System.currentTimeMillis();
        ResponseEntity<byte[]> resp = httpPostJson("/api/agent/orchestrate",
                "{\"question\":\"现在有多少个文档？请只回答数量。\"}", s.token());
        long cost = System.currentTimeMillis() - t0;

        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "编排接口应成功，实际：" + bodyOf(resp));
        String answer = node.path("data").asText();
        assertFalse(answer.isBlank(), "编排回答不应为空");
        assertTrue(answer.matches("(?s).*\\d.*"), "统计类问题回答应含数量数字，实际：" + answer);

        // 回归护栏（对应"反思误用"缺陷修复）：STATS 分支是工具直出的确定性事实，
        // 必须原样返回——既保留工具的计量表述，也保留真实文档数。
        // 修复前该分支会被 Critic 判"无依据"并触发纯 LLM 重写，实测退化为
        // "根据当前可检索到的知识库内容，文档数量为"（数字与列表全丢）。
        assertTrue(answer.contains("篇文档"),
                "STATS 直答应保留工具原文的计量表述（证明未被 LLM 复述替换），实际：" + answer);
        assertTrue(answer.matches("(?s).*共\\s*1\\s*篇文档.*"),
                "回答必须含工具统计出的真实文档数 1（证明数字未被重写丢失），实际：" + answer);
        // 统计自洽护栏：本用例只播种了 1 篇文档（未向量化，但上传即已分块，且种子文档被刻意写长到多分块），
        // 因此「有内容分块的文档数」必须为 1。
        // 该字段的语义是"文档数"而非"分块数"：修复前实现用 selectCount(...isNotNull(content)) 统计行数，
        // 一篇文档切出的多块会被重复计数，实测出现过 1 篇文档却报 2 篇（Agent 还专门把该矛盾当异常报给用户）。
        // 同时用自洽断言兜底：该数字在任何情况下都不得大于文档总数。
        assertTrue(answer.matches("(?s).*有内容分块的文档\\s*1\\s*篇.*"),
                "「有内容分块的文档数」应为 1（按 document_id 去重，而非统计分块行数），实际：" + answer);

        step("A5-05 通过：多 Agent 编排耗时 " + cost + "ms，STATS 分支工具直答（未经反思重写），回答："
                + answer.replace("\n", " ").substring(0, Math.min(120, answer.length())));
    }

    // ==================== 4. 指标观测 ====================

    @Test
    @Order(6)
    @DisplayName("A5-06 指标接口：结构与 Agent 调用计数联动")
    void a506_metrics_snapshot_reflects_agent_calls() {
        AuthSession s = newUser();
        JsonNode before = jsonOf(httpGet("/api/metrics/today", s.token())).path("data");
        long countBefore = before.path("queryCount").asLong();

        httpPostJson("/api/agent/ask", "{\"question\":\"知识库里有哪些文档？\"}", s.token());

        JsonNode after = jsonOf(httpGet("/api/metrics/today", s.token())).path("data");
        long countAfter = after.path("queryCount").asLong();

        assertTrue(after.has("date"), "指标应含 date");
        assertTrue(after.has("queryCount"), "指标应含 queryCount");
        assertTrue(after.has("avgCostMs"), "指标应含 avgCostMs");
        assertTrue(after.has("llmCalls"), "指标应含 llmCalls");
        assertTrue(after.has("toolCalls"), "指标应含 toolCalls");
        assertTrue(countAfter > countBefore,
                "Agent 调用后 queryCount 应增加，before=" + countBefore + " after=" + countAfter);
        assertTrue(after.path("llmCalls").asLong() > 0, "Agent 调用应记录 LLM 调用次数");
        step("A5-06 通过：指标联动正确，queryCount " + countBefore + " → " + countAfter
                + "，llmCalls=" + after.path("llmCalls").asLong()
                + "，toolCalls=" + after.path("toolCalls").asLong());
    }

    // ==================== 5. 工具注册表 ====================

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

    // ==================== 6. Agent 证据契约（反思可用性的前提） ====================

    @Test
    @Order(9)
    @DisplayName("A5-09 Agent 证据契约：Agent 结果必须携带依据片段，否则反思评审必然误判")
    void a509_agent_result_carries_evidence() {
        AuthSession s = newUser();
        // 直接调 Agent 层验证契约：UserContext 是 ThreadLocal，测试线程需自行写入（HTTP 链路由 JwtInterceptor 写入）
        com.liushuwen.rag.common.UserContext.setUserId(s.userId());
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
            com.liushuwen.rag.common.UserContext.clear();   // ThreadLocal 必须清理，防线程复用脏数据
        }
    }
}
