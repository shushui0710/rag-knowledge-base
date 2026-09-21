package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.llm.LlmCircuitBreaker;
import com.liushuwen.rag.llm.LlmUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5f —— Agent 接入对话页与全链路熔断（A5-10 / A5-11 / A5-12）。
 *
 * 【拆分说明】本类只管<b>产品入口</b>这一层：mode 分派、落库可回读、以及熔断在三链上的端到端表现。
 *   与前五个类的区别是——它断言的是"用户真实可达的那条路上发生了什么"，
 *   而不是某个组件单独的行为。
 *
 * 【本类固化的三条结论】
 *   1) mode=agent 经 /api/chat/ask 落库，回答与依据可回读（A5-10）——这是"接进产品"与"只挂个裸 API"的分界线。
 *   2) 非法 mode 静默回退 RAG，不把问答打挂（A5-11）——前端传错字段不该造成 5xx 或空回答。
 *   3) 熔断期 主问答链 / 编排链 / ReAct 链 全部优雅降级且 llmCalls 零增长（A5-12）
 *      ——"全链路熔断"这一结论的端到端事实来源（结构来源见 A5a/A5-02）。
 */
@DisplayName("A5f Agent 接入对话页与全链路熔断（A5-10~12）")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A5f_ChatIntegrationAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private LlmCircuitBreaker breakerSingleton;

    @Autowired
    private com.liushuwen.rag.document.service.MilvusService milvusService;

    @Autowired
    private com.liushuwen.rag.document.service.EmbeddingService embeddingService;

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
        // 若仍只有直连端点（如已删除的 /api/agent/orchestrate）就写不进 chat_message，
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

    @Test
    @Order(12)
    @DisplayName("A5-12 熔断端到端覆盖：主问答链 / 编排链 / ReAct 链在熔断期全部优雅降级，且一次 LLM 请求都不发出")
    void a512_breaker_covers_full_chain_end_to_end() throws Exception {
        AuthSession s = newUser();
        long sessionId = createSession(s.token());
        // 只有检索命中，主问答链才会走到 llmService.chat(...)，那一行才是熔断要拦的地方
        A5Support.seedRetrievableDoc(this, s, milvusService, embeddingService);

        long l0 = A5Support.metricsOf(this, s.token()).path("llmCalls").asLong();

        long backup = A5Support.forceOpenBreaker(breakerSingleton);
        try {
            // ---- ① 主问答链 /api/chat/ask（用户真正在用的链路；修复前它完全没有熔断保护）----
            ResponseEntity<byte[]> ragResp = httpPostJson("/api/chat/ask/" + sessionId,
                    "{\"question\":\"" + A5Support.RETRIEVABLE_SENTENCE.replace("\"", "") + "\"}", s.token());
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
            // 原先打编排接口 /api/agent/orchestrate，该端点已作为纯冗余删除，改走用户真实可达的对话页链路。
            long orchSessionId = createSession(s.token());
            ResponseEntity<byte[]> orchResp = httpPostJson("/api/chat/ask/" + orchSessionId,
                    "{\"question\":\"知识库里有哪些文档？\",\"mode\":\"agent\"}", s.token());
            JsonNode orch = jsonOf(orchResp);
            assertEquals(200, orch.path("code").asInt(),
                    "熔断期编排链应优雅降级而非报错（路由失败回落 DOCUMENT）：" + bodyOf(orchResp));
            assertFalse(orch.path("data").path("content").asText().isBlank(),
                    "熔断期编排链仍应给出用户可见的回答");

            // ---- ④ 熔断的语义就是"直接走兜底、不调 LLM"：llmCalls 必须一动不动 ----
            long l1 = A5Support.metricsOf(this, s.token()).path("llmCalls").asLong();
            assertEquals(l0, l1,
                    "熔断期间不得发出任何 LLM 请求（llmCalls 应保持不变）：" + l0 + " → " + l1);
        } finally {
            A5Support.restoreBreaker(breakerSingleton, backup);
        }

        // ---- ⑤ 复位后主链路恢复真实生成：熔断是可恢复的临时状态，不是永久降级 ----
        JsonNode recovered = ask(s.token(), sessionId, A5Support.RETRIEVABLE_SENTENCE);
        String recoveredAnswer = recovered.path("content").asText();
        assertFalse(recoveredAnswer.isBlank(), "复位后应恢复正常问答");
        assertFalse(recoveredAnswer.contains(LlmUnavailableException.FALLBACK_MESSAGE),
                "复位后不应再返回熔断兜底文案，实际：" + recoveredAnswer);
        step("A5-12 通过：熔断期间 主问答链/编排链/ReAct 链 三条链路全部优雅降级、llmCalls 零增长（"
                + l0 + " 保持不变），复位后恢复真实生成");
    }
}
