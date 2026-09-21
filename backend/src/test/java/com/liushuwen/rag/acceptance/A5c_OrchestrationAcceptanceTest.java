package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.agent.Agent;
import com.liushuwen.rag.agent.AgentResult;
import com.liushuwen.rag.agent.ReportAgent;
import com.liushuwen.rag.agent.controller.AgentController;
import com.liushuwen.rag.common.UserContext;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.llm.LlmUnavailableException;
import com.liushuwen.rag.rag.Route;
import com.liushuwen.rag.rag.RouterService;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5c —— 编排层：意图路由与分支产物（A5-05 / A5-13 / A5-14 / A5-15）。
 *
 * 【拆分说明】本类只管<b>编排层</b>（挑活的那一层）：四类意图怎么分、分完之后各分支产出对不对。
 *   引擎（ReAct）本身的契约在 A5b，产品入口的落库在 A5f。
 *
 * 【本类固化的四条结论】
 *   1) STATS 分支：工具直答，数字与计量表述不得被反思重写抹掉（A5-05）。
 *   2) REPORT 分支：Route/AgentType 都含 REPORT，ReportAgent 已装配，产物必须是<b>报告正文</b>
 *      而不是"报告已生成"的说明（A5-13，09-20 补的假产出防线）。
 *   3) 四类路由逐一可达 + 引擎直连端点的 AgentExecutor 与 REPORT 分支的是<b>同一个实例</b>（A5-14）。
 *   4) HYBRID 分支的【数据概况】/【文档解答】结构不得被重写推平（A5-15）。
 */
@DisplayName("A5c 编排层与意图路由（A5-05 / 13 / 14 / 15）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5c_OrchestrationAcceptanceTest extends AcceptanceSupport {

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

    @Test
    @Order(5)
    @DisplayName("A5-05 编排的 STATS 分支：经产品入口（对话页 mode=agent）由工具直答，数字不得被重写丢失")
    void a505_orchestrator_routes_stats_question() {
        AuthSession s = newUser();
        // 【为什么改走产品入口】本用例原先打编排接口 /api/agent/orchestrate。该端点与产品入口完全重叠
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
        A5Support.seedRetrievableDoc(this, s, milvusService, embeddingService);
        long t0 = A5Support.metricsOf(this, s.token()).path("toolCalls").asLong();
        UserContext.setUserId(s.userId());
        try {
            // 显式点名工具：本用例验的是"分支接线是否通"，意图推断由 ② 的用例负责
            AgentResult r = reportAgent.execute("请使用 generate_report 工具生成一份关于系统参数的说明报告", List.of());
            assertFalse(r.answer().isBlank(), "报告不应为空");
            assertFalse(r.answer().contains(LlmUnavailableException.FALLBACK_MESSAGE),
                    "不应落到熔断兜底文案（说明 LLM 正常）；实际：" + r.answer());
            assertFalse(r.evidence().isEmpty(),
                    "报告分支应把 ReAct 的工具输出作为依据返回，否则对话页「参考来源」为空、反思也无据可核");
            // 【假产出防线】上面三句只验"非空 / 有依据 / 有工具调用"，全都拦不住"LLM 把产物概括成说明"。
            // 补一句语义断言：回答里必须真的有报告正文（长度 + 结构要素），否则本用例判失败。
            A5Support.assertReportBody(r.answer(), "A5-13③ Agent 层直调");
            long t1 = A5Support.metricsOf(this, s.token()).path("toolCalls").asLong();
            assertTrue(t1 > t0,
                    "报告请求应触发工具调用（generate_report）。toolCalls 未增长说明它被当成普通文档问答处理了："
                            + t0 + " → " + t1);
        } finally {
            UserContext.clear();   // ThreadLocal 必须清理
        }

        // ---- ④ 产品可达：对话页「深度思考」(mode=agent) 触发报告后照常落库 ----
        long sessionId = createSession(s.token());
        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"生成一份关于系统参数的说明报告\",\"mode\":\"agent\"}", s.token());
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "报告类提问经对话页应成功，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));
        assertFalse(node.path("data").path("content").asText().isBlank(), "报告不应为空");
        // 产品路径（对话页 HTTP）同样要验"回答是报告正文"，堵住"对话页只回一句说明"的假产出
        A5Support.assertReportBody(node.path("data").path("content").asText(), "A5-13④ 对话页 HTTP");
        JsonNode history = jsonOf(httpGet("/api/chat/history/" + sessionId, s.token())).path("data");
        assertEquals(2, history.size(), "一轮报告应落库 2 条消息（提问 + 报告），实际 " + history.size());

        step("A5-13 通过：路由识别 REPORT → ReportAgent(ReAct + generate_report) 产出报告并带依据，"
                + "经 /api/chat/ask?mode=agent 落库 " + history.size() + " 条");
    }

    @Test
    @Order(14)
    @DisplayName("A5-14 四类路由逐一可达 + 引擎直连端点与 REPORT 分支共用同一个 ReAct 引擎实例")
    void a514_four_routes_and_single_react_engine() throws Exception {
        // ---- ① 结构断言：引擎直连端点与产品入口不是两套实现，而是共用同一个 ReAct 引擎实例 ----
        // 调试/取证侧 = AgentController./api/agent/ask 直连的 AgentExecutor（引擎直连端点）；
        // 产品侧 = 对话页「深度思考」→ OrchestratorAgent 的 REPORT 分支 → ReportAgent → 同一个 AgentExecutor。
        // 之所以用 assertSame 盯住 Bean 实例：只要两者是同一个 Bean，ReAct 循环与工具注册表就只有一份，
        // 埋点（LlmService/ToolRegistry 唯一出口）与熔断（LlmService 唯一出口）自然不存在"某条链路漏记"的口径分裂。
        assertNotNull(agentController, "AgentController 应被装配（引擎直连端点的载体）");
        Object engineBehindApi = A5Support.fieldValueOf(AgentController.class, "agentExecutor", agentController);
        Object engineBehindReportBranch = A5Support.fieldValueOf(ReportAgent.class, "agentExecutor", reportAgent);
        assertSame(engineBehindApi, engineBehindReportBranch,
                "引擎直连端点（/api/agent/ask）与产品入口（对话页 mode=agent → REPORT 分支）必须复用同一个 AgentExecutor 实例，"
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
                + "且引擎直连端点的 AgentExecutor 与 REPORT 分支的 AgentExecutor 为同一实例（同一个引擎，不是两套实现）");
    }

    @Test
    @Order(15)
    @DisplayName("A5-15 HYBRID 组合回答保留【数据概况】/【文档解答】结构（不被反思重写推平）")
    void a515_hybrid_answer_keeps_composite_structure() {
        // 背景（09-20 实测）：OrchestratorAgent 对 STATS 做了 skipReflection 特判，理由是
        // "反思重写会把准确数字换成模型模糊复述"（验收实测过）；但 HYBRID 同样含工具直出的统计数字，
        // 却漏在特判之外 ⇒ 走 Critic 判分。评委看到「【数据概况】说有 1 篇文档」与「【文档解答】说资料
        // 未提及文档数量」两段天然矛盾（一个来自 MySQL 聚合、一个来自向量检索），判不合格并触发重写：
        // 硬拼接的【数据概况】/【文档解答】标签与文档列表被整段抹平，实测 200+ 字结构化成文 → 54 字口语概述。
        // 本用例把"结构标签必须在"钉死，堵住"STATS 修了、HYBRID 漏了"的半修状态。
        AuthSession s = newUser();
        A5Support.seedRetrievableDoc(this, s, milvusService, embeddingService);
        long sessionId = createSession(s.token());

        String question = "现在有多少个文档？另外，文档里讲的切片窗口和重叠是多少？";
        // 前置防御：该问法必须被路由判为 HYBRID（A5-14 已逐一钉住四类路由；此处复核避免用例假失败时误判为缺陷）
        Route preRoute = routerService.route(question);
        assertEquals(Route.HYBRID, preRoute,
                "前置条件：该问法应路由到 HYBRID，实际 " + preRoute + "（路由波动时本用例不成立，先修路由）");

        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"" + question + "\",\"mode\":\"agent\"}", s.token());
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "HYBRID 组合问答经对话页应成功，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));
        String content = node.path("data").path("content").asText();
        String excerpt = content.length() > 160 ? content.substring(0, 160) : content;
        assertFalse(content.isBlank(), "HYBRID 组合回答不应为空");
        assertTrue(content.contains("【数据概况】"),
                "HYBRID 组合回答必须保留【数据概况】结构标签——标签丢失说明它被反思重写推平了"
                        + "（重写 Prompt 面向短问答，会把硬拼接的结构与统计列表整段抹掉）。实际节选：" + excerpt);
        assertTrue(content.contains("【文档解答】"),
                "HYBRID 组合回答必须保留【文档解答】结构标签（同上）。实际节选：" + excerpt);

        step("A5-15 通过：HYBRID 组合回答保留【数据概况】+【文档解答】结构，长度 " + content.length()
                + " 字（未被反思重写推平）");
    }
}
