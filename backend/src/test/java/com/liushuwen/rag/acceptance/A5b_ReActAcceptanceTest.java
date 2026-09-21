package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5b —— ReAct 引擎直连端点（A5-03 / A5-04）。
 *
 * 【拆分说明】本类只管一件事：<b>引擎直连端点 /api/agent/ask 本身的契约</b>——
 *   能真的跑通 ReAct + 工具调用（A5-03），以及参数校验走统一 HTTP 语义（A5-04）。
 *   意图路由、编排分支、产品落库都不在这里（见 A5c / A5f）。
 *
 * 【端点定位】/api/agent/ask 是<b>引擎直连端点</b>（调试与隔离取证用），不是产品入口；
 *   用户唯一的 Agent 入口是对话页「深度思考」→ POST /api/chat/ask + mode=agent。
 */
@DisplayName("A5b ReAct 引擎直连端点（A5-03~04）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5b_ReActAcceptanceTest extends AcceptanceSupport {

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
    @DisplayName("A5-04 引擎直连端点参数校验：空问题被拒，且 HTTP 语义与业务异常路径一致")
    void a504_agent_rejects_blank_question() {
        AuthSession s = newUser();

        // 【用例收缩说明】原先还覆盖 /api/agent/orchestrate（编排接口）。该端点与产品入口
        // （对话页 mode=agent = 同一套 OrchestratorAgent + 多轮历史 + 落库）完全重叠、前端引用为 0，
        // 已作为纯冗余删除，故此处只保留引擎直连端点 /api/agent/ask。
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
        step("A5-04 通过：引擎直连端点 /api/agent/ask 的空问题 HTTP 400 + 「问题不能为空」");
    }
}
