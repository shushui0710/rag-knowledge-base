package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5d —— 指标观测与计数口径（A5-06）。
 *
 * 【拆分说明】本类只管一件事：/api/metrics/today 的<b>计数口径</b>是否"不重不漏"。
 *   这是 G-08（重复计数）与 G-09（覆盖缺口）两条缺陷的回归护栏，因此单独成类——
 *   它验的是"埋点写在哪一层"，与编排逻辑、熔断逻辑的变更原因都不同。
 *
 * 【口径总原则】入口记"次数 + 端到端耗时"（ChatController/AgentController），
 *   依赖出口记"调用次数"（LlmService 记 llmCalls、ToolRegistry.execute 记 toolCalls）。
 *   埋点一旦下沉到会被复用的执行器里，就会重复计数；一旦漏在某个入口，就会漏计。
 */
@DisplayName("A5d 指标观测与计数口径（A5-06）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5d_MetricsAcceptanceTest extends AcceptanceSupport {

    @Test
    @Order(6)
    @DisplayName("A5-06 指标接口：入口计数不重不漏（RAG 链 / 编排链 / ReAct 链逐条精确断言）")
    void a506_metrics_snapshot_reflects_agent_calls() {
        AuthSession s = newUser();

        // ---- 0) 结构 + 基线 ----
        JsonNode before = A5Support.metricsOf(this, s.token());
        assertTrue(before.has("date"), "指标应含 date");
        assertTrue(before.has("queryCount"), "指标应含 queryCount");
        assertTrue(before.has("avgCostMs"), "指标应含 avgCostMs");
        assertTrue(before.has("llmCalls"), "指标应含 llmCalls");
        assertTrue(before.has("toolCalls"), "指标应含 toolCalls");
        long q0 = before.path("queryCount").asLong();
        long l0 = before.path("llmCalls").asLong();
        long t0 = before.path("toolCalls").asLong();

        // ---- 1) 主 RAG 链路（对话页默认模式，不传 mode）----
        // 【G-09 回归护栏】修复前埋点只写在 AgentExecutor 里，本链路完全不计数（llmCalls 增量为 0）。
        // 口径：新用户尚无文档 ⇒ 确定性只发生 1 次 LLM 调用（查询改写），检索为空走兜底文案，
        // 既无生成调用也无工具调用；端到端耗时则由入口层记录。
        long sessionId = createSession(s.token());
        ask(s.token(), sessionId, "这份文档讲了什么？");
        JsonNode afterRag = A5Support.metricsOf(this, s.token());
        assertEquals(q0 + 1, afterRag.path("queryCount").asLong(),
                "RAG 链路应使问答次数 +1（修复前该链路 0 计数）");
        assertEquals(l0 + 1, afterRag.path("llmCalls").asLong(),
                "RAG 链路应精确计 1 次 LLM 调用（仅查询改写），实际 " + afterRag.path("llmCalls").asLong());
        assertEquals(t0, afterRag.path("toolCalls").asLong(),
                "RAG 链路不涉及工具调用，toolCalls 不应变化");

        // ---- 2) 多 Agent 编排链路（STATS 分支，经产品入口 mode=agent）----
        // 【口径变化】原先打编排接口 /api/agent/orchestrate，该端点已作为纯冗余删除（与产品入口完全重叠）。
        // 改走用户真实可达的对话页链路：POST /api/chat/ask + mode=agent。计数口径完全不变——
        // askByAgent 只多做两件不耗 LLM 的事：读一次会话历史（查库）与落库。
        // 【G-08/G-09 回归护栏】STATS 分支是确定性链路：意图路由 1 次 LLM + StatsAgent 组合调用 2 个工具
        // （统计 + 列表），工具直答不触发反思重写。精确断言一次锁两件事：
        //   ① llmCalls 恰好 +1 —— 若沿用修复前"循环内记一次 + recordQuery 按轮数再补一次"的写法，必然翻倍；
        //   ② toolCalls 恰好 +2 —— 若工具计数只写在 AgentExecutor 里，StatsAgent 直调工具的 2 次要被漏记。
        // 前提：「有哪些文档」类问题稳定路由到 STATS（与 A5-05 同一前提，路由 prompt 已显式列出该类问法）。
        long orchSessionId = createSession(s.token());
        ResponseEntity<byte[]> orchestrateResp = httpPostJson("/api/chat/ask/" + orchSessionId,
                "{\"question\":\"知识库里有哪些文档？\",\"mode\":\"agent\"}", s.token());
        JsonNode orchestrate = jsonOf(orchestrateResp);
        assertEquals(200, orchestrate.path("code").asInt(),
                "编排链（产品入口 mode=agent）应成功：" + bodyOf(orchestrateResp));
        JsonNode afterOrch = A5Support.metricsOf(this, s.token());
        assertEquals(q0 + 2, afterOrch.path("queryCount").asLong(),
                "编排链路口也应计 1 次问答（累计 2）");
        assertEquals(l0 + 2, afterOrch.path("llmCalls").asLong(),
                "编排链应精确计 1 次 LLM 调用（仅意图路由；STATS 直答且跳过反思），实际 "
                        + afterOrch.path("llmCalls").asLong());
        assertEquals(t0 + 2, afterOrch.path("toolCalls").asLong(),
                "编排链应精确计 2 次工具调用（统计 + 列表；修复前该链路 0 计数），实际 "
                        + afterOrch.path("toolCalls").asLong());

        // ---- 3) 单 Agent ReAct 链路 ----
        // ReAct 轮数由 LLM 自主决策（不可精确断言），但至少 1 轮决策，且问答次数必须 +1
        httpPostJson("/api/agent/ask", "{\"question\":\"知识库里有哪些文档？\"}", s.token());
        JsonNode afterReAct = A5Support.metricsOf(this, s.token());
        assertEquals(q0 + 3, afterReAct.path("queryCount").asLong(),
                "/api/agent/ask 应计 1 次问答（累计 3）");
        assertTrue(afterReAct.path("llmCalls").asLong() >= l0 + 3,
                "ReAct 每轮都要决策，累计 llmCalls 应 ≥ " + (l0 + 3)
                        + "，实际 " + afterReAct.path("llmCalls").asLong());

        step("A5-06 通过：入口计数不重不漏（RAG/编排/ReAct 三链均计入）——queryCount "
                + q0 + "→" + afterReAct.path("queryCount").asLong()
                + "，llmCalls " + l0 + "→" + afterReAct.path("llmCalls").asLong()
                + "，toolCalls " + t0 + "→" + afterReAct.path("toolCalls").asLong());
    }
}
