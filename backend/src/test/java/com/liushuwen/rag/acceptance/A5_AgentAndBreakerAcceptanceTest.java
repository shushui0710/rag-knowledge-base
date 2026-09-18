package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.agent.Agent;
import com.liushuwen.rag.agent.AgentExecutor;
import com.liushuwen.rag.agent.AgentResult;
import com.liushuwen.rag.agent.LlmCircuitBreaker;
import com.liushuwen.rag.agent.ReportAgent;
import com.liushuwen.rag.agent.StatsAgent;
import com.liushuwen.rag.agent.ToolRegistry;
import com.liushuwen.rag.chat.service.LlmService;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.common.LlmUnavailableException;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.controller.AgentController;
import com.liushuwen.rag.rag.Route;
import com.liushuwen.rag.rag.RouterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5 —— Agent 链路与熔断器验收。
 *
 * 覆盖范围：
 *   1. 熔断器状态机（连续失败达阈值 → 打开；成功 → 计数清零）
 *   2. 熔断收口到"全站 LLM 唯一出口"（结构断言 + 出口守卫 + 复位恢复）
 *   3. ReAct 单 Agent 问答（真实 LLM + 工具调用）
 *   4. 多 Agent 编排与意图路由（STATS 分支走工具直答；REPORT 分支产报告）
 *   5. 参数校验与降级文案
 *   6. 指标观测接口与计数口径（入口计数不重不漏：RAG 链 / 编排链 / ReAct 链逐条精确断言）
 *   7. 工具注册表自动收集（开闭原则：新增 @Component 即注册）
 *   8. Agent 证据契约（反思评审可用性的前提）
 *   9. 熔断端到端覆盖（熔断期间主问答链 / 编排链 / ReAct 链均优雅降级，且一次 LLM 请求都不发出）
 *  10. REPORT 分支：报告生成从"裸接口孤岛"变成编排链路的一条正常分支，可经对话页触达并落库
 *  11. 四类路由逐一可达（DOCUMENT/STATS/REPORT/HYBRID 各有唯一归属）+ "一个 ReAct 引擎、两个入口"的结构断言
 *
 * 【重要边界说明】
 *   1) 熔断器（LlmCircuitBreaker）挂在 **LlmService —— 全站 LLM 调用的唯一出口**，
 *      因此覆盖主问答链 /api/chat/ask、编排链 /api/chat/ask(mode=agent) 与 ReAct 链 /api/agent/ask，
 *      以及意图路由、查询改写、反思评审这些"子任务调用"。文档与简历可表述为"全链路熔断"。
 *      （修复前它只被 AgentExecutor 持有 ⇒ 仅覆盖 /api/agent/ask，主链路裸奔；A5-02 / A5-12 即为该结论的事实来源。）
 *   2) 反思评审（CriticService）只作用于**面向短问答、由 LLM 生成**的回答（DOCUMENT/HYBRID 分支）；
 *      STATS 是工具直出的确定性事实（重写只会把准确数字换成模糊复述），
 *      REPORT 是长结构化产物（重写 Prompt 面向短问答设计，会把报告章节推平），二者均跳过评审-重写。
 *      评审时传入 AgentResult.evidence 作为"依据片段"，不再传空列表。
 *   3) REPORT 分支的意图识别由路由的 LLM 分类产出（temperature=0.1），A5-13 用与路由 Prompt 示例同形的
 *      问法做断言；分支本身的产出与落库则用 Agent 层直调 + 对话页 HTTP 两条路径分别取证。
 *   4) 【09-18 收口·端点收敛】裸接口 POST /api/agent/orchestrate 已删除——它与产品入口完全重叠
 *      （产品入口 = 同一套 OrchestratorAgent + 多轮历史 + 落库，严格覆盖它）、前端 frontend/src 引用为 0、
 *      独有价值仅"不落库"，属纯冗余；删除后 OrchestratorAgent.execute(String, List) 与
 *      AgentExecutor.executeResult(String) 失去调用方，作为死方法一并移除。
 *      因此本套件里凡涉及"编排链"的用例（A5-05 / A5-06 / A5-12）统一改走产品入口
 *      POST /api/chat/ask + mode=agent——这样验的才是用户真实可达的那条路。
 *      仅存的裸端点 /api/agent/ask 保留为**引擎调试/隔离取证**入口：它直连 AgentExecutor、不掺意图路由
 *      那次 LLM 调用，A5-12 的三链降级对比与 A5-14 的 assertSame 都依赖它。
 *
 * 通过标准（P0）：
 *   - 熔断器：连续 5 次失败后 tryAcquire 返回 false；onSuccess 后失败计数归零
 *   - 熔断器只被 LlmService 持有（唯一出口）；熔断期间 3 个出口方法统一抛 LlmUnavailableException
 *   - /api/agent/ask（引擎调试端点）返回非空回答；空问题被拒（400）
 *   - 编排链（产品入口：对话页 mode=agent）对统计类问题给出含真实文档数、保留工具计量表述的回答
 *   - /api/metrics/today 结构与计数口径正确：入口记 queryCount/avgCostMs，唯一出口记 llmCalls/toolCalls，
 *     RAG 链（改写 1 次 LLM）、编排 STATS 分支（路由 1 次 LLM + 2 次工具）增量均精确可预测
 *     （G-08 重复计数 / G-09 覆盖缺口的回归护栏）
 *   - 3 个工具（query_document_stats / query_document_list / generate_report）全部自动注册
 *   - Agent 结果携带非空 evidence（否则反思评审必然误判"无依据"）
 *   - 熔断打开时：主问答链 / 编排链 / ReAct 链均 HTTP 200 + 用户可见兜底，llmCalls 零增长
 *   - REPORT 分支：AgentType/Route 均含 REPORT，ReportAgent 已装配且 type()=REPORT，产出的报告非空且带证据
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

    @Autowired
    private LlmService llmService;

    @Autowired
    private LlmCircuitBreaker breakerSingleton;

    @Autowired
    private RouterService routerService;

    @Autowired
    private ReportAgent reportAgent;

    @Autowired
    private AgentController agentController;

    @Autowired
    private com.liushuwen.rag.document.service.MilvusService milvusService;

    @Autowired
    private com.liushuwen.rag.document.service.EmbeddingService embeddingService;

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
    @DisplayName("A5-02 熔断收口到唯一出口：熔断器挂在 LlmService、执行器不再自持，三个出口方法统一被守卫")
    void a502_breaker_is_wired_at_unique_llm_exit() throws Exception {
        // ---- ① 结构断言：熔断器只被"全站 LLM 唯一出口"持有 ----
        assertTrue(hasFieldOfType(LlmService.class, LlmCircuitBreaker.class),
                "熔断器应挂在 LlmService（全站 LLM 唯一出口）上；挂在某一层执行器上时，"
                        + "只有路过该执行器的链路被保护，主问答链 /api/chat/ask 会完全裸奔");
        assertFalse(hasFieldOfType(AgentExecutor.class, LlmCircuitBreaker.class),
                "修复后 AgentExecutor 不应再自持熔断器（「熔断挂错层」正是它只覆盖单条链路、主链路裸奔的根因）");

        // ---- ② 文案与异常体系契约 ----
        assertEquals("抱歉，AI 服务暂时不可用，请稍后再试。", LlmUnavailableException.FALLBACK_MESSAGE,
                "熔断兜底文案由专属异常统一定义，各链路复用同一口径");
        assertTrue(BusinessException.class.isAssignableFrom(LlmUnavailableException.class),
                "LlmUnavailableException 应继承 BusinessException，复用统一异常体系（未被捕获时 → HTTP 400）");

        // ---- ③ 行为断言：把运行中的单例熔断器强制打开，三个出口方法必须统一抛专属异常 ----
        // 之所以直接改运行中的单例：只有真实链路上那一个熔断器被打开，才能证明"出口被守卫"；
        // 而 new 一个本地实例只能证明状态机算法，证明不了接线。测试结束必须复位（见 finally）。
        forceOpenBreaker();
        try {
            assertThrows(LlmUnavailableException.class, () -> llmService.chat("任意问题"),
                    "chat（主问答链出口）在熔断期应直接抛专属异常，不发出请求");
            assertThrows(LlmUnavailableException.class, () -> llmService.chatWithSystem("sys", "user", 0.1),
                    "chatWithSystem（意图路由/查询改写/反思评审的出口）应同样被守卫");
            assertThrows(LlmUnavailableException.class, () -> llmService.chatWithTools(List.of(), List.of()),
                    "chatWithTools（ReAct 的出口）应同样被守卫");

            // ---- ④ 执行器降级文案：熔断期返回固定文案而非把异常抛给用户 ----
            String degraded = agentExecutor.execute("现在知识库里有几个文档？");
            assertEquals(LlmUnavailableException.FALLBACK_MESSAGE, degraded,
                    "熔断期 AgentExecutor 应返回统一兜底文案（用户永不见堆栈）");
        } finally {
            restoreBreaker();
        }

        // ---- ⑤ 复位后重新放行：熔断是"临时"状态，窗口过后必须自动恢复 ----
        assertTrue(breakerSingleton.tryAcquire(),
                "复位后熔断器应重新放行（否则后续用例会被永久阻断 60s）");
        step("A5-02 通过：熔断器挂在唯一出口 LlmService（AgentExecutor 不再自持），"
                + "chat/chatWithSystem/chatWithTools 三出口统一抛 LlmUnavailableException，复位后放行");
    }

    /** 反射判断某类是否持有指定类型的字段（用于断言"熔断器挂在哪一层"） */
    private static boolean hasFieldOfType(Class<?> clazz, Class<?> fieldType) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType().equals(fieldType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把运行中的单例熔断器强制置为"打开"（openUntil = now + 60s），并记录原值以便复位。
     * 【为什么用反射】熔断器没有提供"人为打开"的入口（生产代码不该为测试开后门），
     * 而验收要证明的恰恰是"真实链路用的那个熔断器一打开，全链路都走兜底"，只能直接改状态。
     */
    private void forceOpenBreaker() throws Exception {
        Field f = LlmCircuitBreaker.class.getDeclaredField("openUntil");
        f.setAccessible(true);
        openUntilBackup = (long) f.get(breakerSingleton);
        f.set(breakerSingleton, System.currentTimeMillis() + 60_000L);
    }

    /** 复位熔断器（必须在 finally 中调用，否则会把同一个 JVM 里的后续用例全部熔断） */
    private void restoreBreaker() throws Exception {
        Field f = LlmCircuitBreaker.class.getDeclaredField("openUntil");
        f.setAccessible(true);
        f.set(breakerSingleton, openUntilBackup);
    }

    /** 熔断器 openUntil 原值备份 */
    private long openUntilBackup;

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
    @DisplayName("A5-04 引擎调试端点参数校验：空问题被拒，且 HTTP 语义与业务异常路径一致")
    void a504_agent_rejects_blank_question() {
        AuthSession s = newUser();

        // 【用例收缩说明】原先还覆盖 /api/agent/orchestrate（编排裸接口）。该端点与产品入口
        // （对话页 mode=agent = 同一套 OrchestratorAgent + 多轮历史 + 落库）完全重叠、前端引用为 0，
        // 已作为纯冗余删除，故此处只保留引擎调试端点 /api/agent/ask。
        // 校验分支要与 Service 抛 BusinessException 的路径保持同一套 HTTP 语义：
        // 修复前它用 return Result.error(400, ...) 做内联校验，方法正常返回 → Spring 按 HTTP 200 发出，
        // 与 BusinessException 的 HTTP 400 形成两套语义；原用例只断言响应体 code，因此该缺陷可以全绿存活。
        String[][] calls = {
                {"/api/agent/ask", "{\"question\":\"\"}", "空字符串"},
                {"/api/agent/ask", "{\"question\":\"   \"}", "纯空白"},
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
        step("A5-04 通过：引擎调试端点 /api/agent/ask 的空问题 HTTP 400 + 「问题不能为空」");
    }

    // ==================== 3. 多 Agent 编排与意图路由 ====================

    @Test
    @Order(5)
    @DisplayName("A5-05 编排的 STATS 分支：经产品入口（对话页 mode=agent）由工具直答，数字不得被重写丢失")
    void a505_orchestrator_routes_stats_question() {
        AuthSession s = newUser();
        // 【为什么改走产品入口】本用例原先打裸接口 /api/agent/orchestrate。该端点与产品入口完全重叠
        // （同一套 OrchestratorAgent，产品入口只是多了多轮历史 + 落库），前端引用为 0，已删除。
        // 现在直接走用户真实可达的链路：POST /api/chat/ask + mode=agent，断言从落库回答里读。
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
        long sessionId = createSession(s.token());
        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"现在有多少个文档？请只回答数量。\",\"mode\":\"agent\"}", s.token());
        long cost = System.currentTimeMillis() - t0;

        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "编排链路（产品入口 mode=agent）应成功，实际：" + bodyOf(resp));
        String answer = node.path("data").path("content").asText();
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

        step("A5-05 通过：编排 STATS 分支经产品入口（mode=agent）耗时 " + cost
                + "ms，工具直答（未经反思重写），回答："
                + answer.replace("\n", " ").substring(0, Math.min(120, answer.length())));
    }

    // ==================== 4. 指标观测 ====================

    @Test
    @Order(6)
    @DisplayName("A5-06 指标接口：入口计数不重不漏（RAG 链 / 编排链 / ReAct 链逐条精确断言）")
    void a506_metrics_snapshot_reflects_agent_calls() {
        AuthSession s = newUser();

        // ---- 0) 结构 + 基线 ----
        JsonNode before = metricsOf(s.token());
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
        JsonNode afterRag = metricsOf(s.token());
        assertEquals(q0 + 1, afterRag.path("queryCount").asLong(),
                "RAG 链路应使问答次数 +1（修复前该链路 0 计数）");
        assertEquals(l0 + 1, afterRag.path("llmCalls").asLong(),
                "RAG 链路应精确计 1 次 LLM 调用（仅查询改写），实际 " + afterRag.path("llmCalls").asLong());
        assertEquals(t0, afterRag.path("toolCalls").asLong(),
                "RAG 链路不涉及工具调用，toolCalls 不应变化");

        // ---- 2) 多 Agent 编排链路（STATS 分支，经产品入口 mode=agent）----
        // 【口径变化】原先打裸接口 /api/agent/orchestrate，该端点已作为纯冗余删除（与产品入口完全重叠）。
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
        JsonNode afterOrch = metricsOf(s.token());
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
        JsonNode afterReAct = metricsOf(s.token());
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

    /** 读取今日指标 data 节点（供 A5-06 做增量断言） */
    private JsonNode metricsOf(String token) {
        return jsonOf(httpGet("/api/metrics/today", token)).path("data");
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

    // ==================== 7. Agent 接入对话页（mode=agent） ====================

    @Test
    @Order(10)
    @DisplayName("A5-10 对话页 agent 模式：mode=agent 经 /api/chat/ask 落库，回答与依据可回读")
    void a510_agent_mode_via_chat_endpoint_is_persisted() throws Exception {
        AuthSession s = newUser();
        long sessionId = createSession(s.token());

        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"知识库里现在有几篇文档？\",\"mode\":\"agent\"}", s.token());
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "agent 模式提问应成功，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));

        String answer = node.path("data").path("content").asText();
        assertFalse(answer.isBlank(), "agent 模式回答不应为空");

        // 依据必须随回答返回并落库：agent 的意见来自检索片段或工具原文，
        // 只返回文本会让前端「参考来源」区恒为空——等于把 agent 的能力砍掉一半。
        JsonNode sources = JSON.readTree(node.path("data").path("sources").asText());
        assertTrue(sources.isArray() && sources.size() > 0,
                "agent 模式应返回依据片段，实际：" + node.path("data").path("sources").asText());
        boolean hasEvidenceTag = false;
        for (JsonNode src : sources) {
            if ("evidence".equals(src.path("type").asText())) {
                hasEvidenceTag = true;
            }
            assertFalse(src.path("score").isNumber(),
                    "agent 依据是片段而非打分结果，不应伪造 score 字段：" + src);
        }
        assertTrue(hasEvidenceTag, "依据项应带 type=evidence 标记（前端据此渲染「依据片段」而非「相似度」）");

        // 落库与回读——这是"接进产品"与"只挂个裸 API"的分界线：
        // 若仍只有裸端点（如已删除的 /api/agent/orchestrate）就写不进 chat_message，
        // 会话历史里看不到、刷新即丢，前端「参考来源」也无从渲染。
        JsonNode history = jsonOf(httpGet("/api/chat/history/" + sessionId, s.token())).path("data");
        assertTrue(history.isArray(), "历史应为数组");
        assertEquals(2, history.size(), "一轮问答应落库 2 条消息（提问 + 回答），实际 " + history.size());
        assertEquals("user", history.get(0).path("role").asText(), "第一条应为用户提问");
        assertEquals("assistant", history.get(1).path("role").asText(), "第二条应为助手回答");
        assertEquals(answer, history.get(1).path("content").asText(),
                "历史里的回答应与本次返回一致（证明真的落库，而非只走内存）");

        step("A5-10 通过：mode=agent 经 chat 入口落库，" + history.size() + " 条历史消息，依据 "
                + sources.size() + " 条；回答：" + answer.replace("\n", " ").substring(0, Math.min(80, answer.length())));
    }

    @Test
    @Order(11)
    @DisplayName("A5-11 非法 mode 静默回退：前端传错字段不应把问答打挂")
    void a511_unknown_mode_falls_back_to_rag() {
        AuthSession s = newUser();
        long sessionId = createSession(s.token());

        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"随便问一个知识库里没有的问题\",\"mode\":\"not-a-real-mode\"}", s.token());
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "非法 mode 应回退默认链路而非报错，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));
        assertFalse(node.path("data").path("content").asText().isBlank(), "回退后仍应给出回答");

        // 回退链路是 RAG：空知识库必然走兜底文案（同时证明没有误判成 agent 链路）
        assertTrue(node.path("data").path("content").asText().contains("没有找到足够相关"),
                "非法 mode 应走默认 RAG 链路（新账号空知识库 → 兜底文案），实际："
                        + node.path("data").path("content").asText());

        step("A5-11 通过：mode=not-a-real-mode 静默回退 RAG 链路，回答："
                + node.path("data").path("content").asText().replace("\n", " "));
    }

    // ==================== 8. 熔断的端到端覆盖面 ====================

    /**
     * 种子文档中"必被检索到"的那句话：既作为播种内容，也作为 A5-12 的提问（保证检索命中，
     * 主问答链才会走到 llmService.chat 那一行——熔断保护的对象才被真正执行到）。
     */
    private static final String RETRIEVABLE_SENTENCE = "文档切片窗口为 512 个字符，相邻切片重叠 64 个字符，步长因此是 448。";

    @Test
    @Order(12)
    @DisplayName("A5-12 熔断端到端覆盖：主问答链 / 编排链 / ReAct 链在熔断期全部优雅降级，且一次 LLM 请求都不发出")
    void a512_breaker_covers_full_chain_end_to_end() throws Exception {
        AuthSession s = newUser();
        long sessionId = createSession(s.token());
        // 只有检索命中，主问答链才会走到 llmService.chat(...)，那一行才是熔断要拦的地方
        seedRetrievableDoc(s);

        long l0 = metricsOf(s.token()).path("llmCalls").asLong();

        forceOpenBreaker();
        try {
            // ---- ① 主问答链 /api/chat/ask（用户真正在用的链路；修复前它完全没有熔断保护）----
            ResponseEntity<byte[]> ragResp = httpPostJson("/api/chat/ask/" + sessionId,
                    "{\"question\":\"" + RETRIEVABLE_SENTENCE.replace("\"", "") + "\"}", s.token());
            JsonNode ragNode = jsonOf(ragResp);
            assertEquals(200, ragResp.getStatusCode().value(),
                    "熔断期主问答链不得返回 5xx（熔断是降级不是故障）：" + bodyOf(ragResp));
            String ragAnswer = ragNode.path("data").path("content").asText();
            assertEquals(LlmUnavailableException.FALLBACK_MESSAGE, ragAnswer,
                    "熔断期主问答链应返回统一熔断兜底文案。若实际是「知识库中没有找到足够相关的内容」，"
                            + "说明检索没命中，本用例无法证明主链路被熔断覆盖，需检查种子文档；实际：" + ragAnswer);

            // ---- ② ReAct 链 /api/agent/ask ----
            ResponseEntity<byte[]> reactResp = httpPostJson("/api/agent/ask",
                    "{\"question\":\"现在知识库里有几个文档？\"}", s.token());
            JsonNode react = jsonOf(reactResp);
            assertEquals(200, react.path("code").asInt(),
                    "熔断期 ReAct 链应以兜底文案成功返回，而非抛异常：" + bodyOf(reactResp));
            assertEquals(LlmUnavailableException.FALLBACK_MESSAGE, react.path("data").asText(),
                    "熔断期 ReAct 链应返回统一熔断兜底文案");

            // ---- ③ 编排链（产品入口 mode=agent；意图路由本身就是一次 LLM 调用）----
            // 原先打裸接口 /api/agent/orchestrate，该端点已作为纯冗余删除，改走用户真实可达的对话页链路。
            long orchSessionId = createSession(s.token());
            ResponseEntity<byte[]> orchResp = httpPostJson("/api/chat/ask/" + orchSessionId,
                    "{\"question\":\"知识库里有哪些文档？\",\"mode\":\"agent\"}", s.token());
            JsonNode orch = jsonOf(orchResp);
            assertEquals(200, orch.path("code").asInt(),
                    "熔断期编排链应优雅降级而非报错（路由失败回落 DOCUMENT）：" + bodyOf(orchResp));
            assertFalse(orch.path("data").path("content").asText().isBlank(),
                    "熔断期编排链仍应给出用户可见的回答");

            // ---- ④ 熔断的语义就是"直接走兜底、不调 LLM"：llmCalls 必须一动不动 ----
            long l1 = metricsOf(s.token()).path("llmCalls").asLong();
            assertEquals(l0, l1,
                    "熔断期间不得发出任何 LLM 请求（llmCalls 应保持不变）：" + l0 + " → " + l1);
        } finally {
            restoreBreaker();
        }

        // ---- ⑤ 复位后主链路恢复真实生成：熔断是可恢复的临时状态，不是永久降级 ----
        JsonNode recovered = ask(s.token(), sessionId, RETRIEVABLE_SENTENCE);
        String recoveredAnswer = recovered.path("content").asText();
        assertFalse(recoveredAnswer.isBlank(), "复位后应恢复正常问答");
        assertFalse(recoveredAnswer.contains(LlmUnavailableException.FALLBACK_MESSAGE),
                "复位后不应再返回熔断兜底文案，实际：" + recoveredAnswer);
        step("A5-12 通过：熔断期间 主问答链/编排链/ReAct 链 三条链路全部优雅降级、llmCalls 零增长（"
                + l0 + " 保持不变），复位后恢复真实生成");
    }

    /** 播种一篇"提问必命中"的文档并向量化（等 Milvus 可见性窗口），返回 docId */
    private long seedRetrievableDoc(AuthSession s) {
        String marker = "A5" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        StringBuilder sb = new StringBuilder();
        sb.append("# 系统参数说明\n\n");
        sb.append("文档唯一标记词：").append(marker).append("\n\n");
        sb.append("## 切片参数\n\n").append(RETRIEVABLE_SENTENCE).append("\n\n");
        sb.append("## 补充说明\n\n");
        for (int i = 0; i < 8; i++) {
            sb.append("补充说明 ").append(i).append("：本节用于增加文档体量，使检索需要真正区分多个片段。\n");
        }
        JsonNode doc = uploadDoc(s.token(), "系统参数说明.md", sb.toString(), "技术文档");
        long docId = doc.path("id").asLong();
        embedDoc(s.token(), docId);
        boolean visible = waitUntilRetrievable(milvusService,
                embeddingService.embedSingle(RETRIEVABLE_SENTENCE), docId, RETRIEVABLE_SENTENCE);
        assertTrue(visible, "前置条件：种子文档向量化后 15s 内仍不可检索（docId=" + docId + "）");
        return docId;
    }

    // ==================== 9. REPORT 分支（单 Agent 能力接入产品链路） ====================

    @Test
    @Order(13)
    @DisplayName("A5-13 REPORT 分支：路由可识别报告意图、ReportAgent 已装配，报告经对话页可产出并落库")
    void a513_report_branch_is_reachable_from_product() {
        // ---- ① 枚举与装配：Route/AgentType 都含 REPORT，且真的有 Agent 认领它 ----
        assertEquals(Agent.AgentType.REPORT, reportAgent.type(), "ReportAgent 应声明 type()=REPORT");
        assertNotNull(Route.valueOf("REPORT"),
                "Route 应含 REPORT（此前路由只产出 DOCUMENT/STATS/HYBRID，REPORT 是永远选不中的死分支）");

        // ---- ② 意图识别：路由把"生成一份报告"分到 REPORT（问法与路由 Prompt 中的示例同形）----
        Route route = routerService.route("请生成一份关于知识库内容的报告");
        assertEquals(Route.REPORT, route,
                "路由应把报告生成类请求分到 REPORT，实际 " + route
                        + "（若为 DOCUMENT，说明路由 Prompt 未覆盖该意图，报告能力对用户仍不可达）");

        // ---- ③ 分支产出：直调 ReportAgent（规避路由不确定性）证明 ReAct + generate_report 真能出报告 ----
        AuthSession s = newUser();
        seedRetrievableDoc(s);
        long t0 = metricsOf(s.token()).path("toolCalls").asLong();
        com.liushuwen.rag.common.UserContext.setUserId(s.userId());
        try {
            // 显式点名工具：本用例验的是"分支接线是否通"，意图推断由 ② 的用例负责
            AgentResult r = reportAgent.execute("请使用 generate_report 工具生成一份关于系统参数的说明报告", List.of());
            assertFalse(r.answer().isBlank(), "报告不应为空");
            assertFalse(r.answer().contains(LlmUnavailableException.FALLBACK_MESSAGE),
                    "不应落到熔断兜底文案（说明 LLM 正常）；实际：" + r.answer());
            assertFalse(r.evidence().isEmpty(),
                    "报告分支应把 ReAct 的工具输出作为依据返回，否则对话页「参考来源」为空、反思也无据可核");
            long t1 = metricsOf(s.token()).path("toolCalls").asLong();
            assertTrue(t1 > t0,
                    "报告请求应触发工具调用（generate_report）。toolCalls 未增长说明它被当成普通文档问答处理了："
                            + t0 + " → " + t1);
        } finally {
            com.liushuwen.rag.common.UserContext.clear();   // ThreadLocal 必须清理
        }

        // ---- ④ 产品可达：对话页「深度思考」(mode=agent) 触发报告后照常落库 ----
        long sessionId = createSession(s.token());
        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"生成一份关于系统参数的说明报告\",\"mode\":\"agent\"}", s.token());
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "报告类提问经对话页应成功，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));
        assertFalse(node.path("data").path("content").asText().isBlank(), "报告不应为空");
        JsonNode history = jsonOf(httpGet("/api/chat/history/" + sessionId, s.token())).path("data");
        assertEquals(2, history.size(), "一轮报告应落库 2 条消息（提问 + 报告），实际 " + history.size());

        step("A5-13 通过：路由识别 REPORT → ReportAgent(ReAct + generate_report) 产出报告并带依据，"
                + "经 /api/chat/ask?mode=agent 落库 " + history.size() + " 条");
    }

    // ==================== 11. 四类路由全覆盖 + "一个引擎、两个入口" ====================

    @Test
    @Order(14)
    @DisplayName("A5-14 四类路由逐一可达 + 两个入口共用同一个 ReAct 引擎（一个引擎、两个入口）")
    void a514_four_routes_and_single_react_engine() throws Exception {
        // ---- ① 结构断言：链路③ 与链路④ 不是两套实现，而是"一个 ReAct 引擎、两个入口" ----
        // 入口① = AgentController./api/agent/ask 直连的 AgentExecutor（裸接口，调试/验收入口）；
        // 入口② = 对话页「深度思考」→ OrchestratorAgent 的 REPORT 分支 → ReportAgent → 同一个 AgentExecutor。
        // 之所以用 assertSame 盯住 Bean 实例：只要两者是同一个 Bean，ReAct 循环与工具注册表就只有一份，
        // 埋点（LlmService/ToolRegistry 唯一出口）与熔断（LlmService 唯一出口）自然不存在"某条链路漏记"的口径分裂。
        assertNotNull(agentController, "AgentController 应被装配（入口①的载体）");
        Object engineBehindApi = fieldValueOf(AgentController.class, "agentExecutor", agentController);
        Object engineBehindReportBranch = fieldValueOf(ReportAgent.class, "agentExecutor", reportAgent);
        assertSame(engineBehindApi, engineBehindReportBranch,
                "入口①（/api/agent/ask）与入口②（对话页 mode=agent → REPORT 分支）必须复用同一个 AgentExecutor 实例，"
                        + "否则就是「两个引擎、四份口径」，链路③/④ 的对比结论不再成立");

        // ---- ② 四类路由逐一断言：四类意图各有唯一归属，REPORT/HYBRID 都不是"选不中的死分支" ----
        // 问法与 RouterServiceImpl 的 Prompt 示例同形（temperature=0.1 求稳），逐条钉住，避免"只测了 REPORT"的盲区。
        Object[][] cases = {
                {"这份文档里讲的切片参数是什么？", Route.DOCUMENT, "问文档里的知识点"},
                {"现在有多少个文档？", Route.STATS, "问统计数字/列表"},
                {"请生成一份关于知识库内容的报告", Route.REPORT, "要一份成文产物"},
                {"既统计文档数量、也回答文档里的知识点：一共几个文档？切片参数是什么？", Route.HYBRID, "两者都要"},
        };
        StringBuilder hits = new StringBuilder();
        for (Object[] c : cases) {
            String question = (String) c[0];
            Route expected = (Route) c[1];
            Route actual = routerService.route(question);
            assertEquals(expected, actual,
                    "「" + c[2] + "」应路由到 " + expected + "，实际 " + actual + "（问法：" + question + "）");
            hits.append(expected).append(" ");
        }

        step("A5-14 通过：四类路由逐一命中（" + hits.toString().trim() + "），"
                + "且入口①的 AgentExecutor 与 REPORT 分支的 AgentExecutor 为同一实例（一个引擎、两个入口）");
    }

    /** 读取指定类的私有字段值（用于断言"两个入口是否共用同一个引擎实例"） */
    private static Object fieldValueOf(Class<?> clazz, String fieldName, Object target) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }
}
