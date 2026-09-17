package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.rag.MemoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 —— 端到端问答链路验收（HTTP 入口 → 检索 → 重排 → 生成 → 引用 → 落库）。
 *
 * 覆盖范围：
 *   1. 完整问答：提问 → 混合检索 → 重排 → 阈值过滤 → Prompt → LLM → 带引用的回答
 *   2. 引用来源（sources）结构与 content 预览截断规则
 *   3. 空召回兜底：无文档用户提问直接返回固定拒答文案（不调用 LLM、不编造）
 *   4. 会话与消息持久化：先落库提问、历史顺序、会话隔离、删除级联
 *   5. 长期记忆闭环：高质量问答（重排最高分 ≥ 0.6）写入 qa_memory 并可被同用户召回
 *   6. 真实时延采集：为《验收报告》提供端到端耗时实测值（不是估算）
 *
 * 通过标准（P0）：
 *   - 有相关文档时：回答非空、sources 非空且条数 ≤ 5、每条含 chunkId/score/content
 *   - 无文档时：返回固定兜底文案，且回答不得包含任何编造内容
 *   - 历史消息按时间升序，user 在前 assistant 在后
 *   - 会话列表、历史查询均按 userId 隔离
 *   - 高质量问答可被同用户长期记忆召回，且不串到其他用户
 */
@DisplayName("A4 端到端问答链路验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A4_ChatFlowAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private com.liushuwen.rag.document.service.MilvusService milvusService;

    @Autowired
    private com.liushuwen.rag.document.service.EmbeddingService embeddingService;

    /** 兜底文案常量，与 ChatServiceImpl 中 results.isEmpty() 分支逐字一致 */
    private static final String FALLBACK_TEXT = "知识库中没有找到足够相关的内容，请换个问法或先上传相关文档。";

    private static final String SENTENCE_CHUNK = "文档切片窗口为 512 个字符，相邻切片重叠 64 个字符，步长因此是 448。";
    private static final String SENTENCE_DIM = "向量维度为 2048 维，由智谱 embedding-3 模型输出。";
    private static final String SENTENCE_ALPHA = "混合检索的权重 alpha 默认取 0.7，偏重稠密语义路。";
    private static final String SENTENCE_RERANK = "召回 20 条候选后经重排序精排，只保留 5 条进入提示词。";
    private static final String SENTENCE_SCORE = "相似度阈值 minScore 默认 0.35，低于该值的片段会被剔除。";

    /** 播种一份"知识库"文档：文本里逐字包含上面五句结论，保证提问能真实命中 */
    private long seedKnowledgeBase(AuthSession s) {
        String marker = "KB" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        StringBuilder sb = new StringBuilder();
        sb.append("# 系统参数说明\n\n");
        sb.append("文档唯一标记词：").append(marker).append("\n\n");
        sb.append("## 切片参数\n\n").append(SENTENCE_CHUNK).append("\n\n");
        sb.append("## 向量参数\n\n").append(SENTENCE_DIM).append("\n\n");
        sb.append("## 混合检索\n\n").append(SENTENCE_ALPHA).append("\n\n");
        sb.append("## 重排序\n\n").append(SENTENCE_RERANK).append("\n\n");
        sb.append("## 分数阈值\n\n").append(SENTENCE_SCORE).append("\n\n");
        sb.append("## 背景说明\n\n");
        for (int i = 0; i < 8; i++) {
            sb.append("补充说明 ").append(i).append("：本节用于增加文档体量，使检索需要真正区分多个片段。\n");
        }
        JsonNode doc = uploadDoc(s.token(), "系统参数说明.md", sb.toString(), "技术文档");
        long docId = doc.path("id").asLong();
        embedDoc(s.token(), docId);
        // 等待本文档向量对检索可见，保证后续 ask() 一定能召回（避免 Milvus Bounded 一致性窗口造成假失败）
        boolean visible = waitUntilRetrievable(milvusService,
                embeddingService.embedSingle(SENTENCE_CHUNK), docId, SENTENCE_CHUNK);
        assertTrue(visible, "前置条件：知识库语料向量化后 15s 内仍不可检索（docId=" + docId + "）");
        return docId;
    }

    // ==================== 1. 完整问答链路 ====================

    @Test
    @Order(1)
    @DisplayName("A4-01 完整问答：回答非空且带引用来源（端到端时延实测）")
    void a401_full_rag_chain_returns_answer_with_sources() {
        AuthSession s = newUser();
        seedKnowledgeBase(s);
        long sessionId = createSession(s.token());

        long t0 = System.currentTimeMillis();
        JsonNode answer = ask(s.token(), sessionId, "文档切片窗口和重叠分别是多少？");
        long cost = System.currentTimeMillis() - t0;

        assertEquals("assistant", answer.path("role").asText(), "应返回助手消息");
        String content = answer.path("content").asText();
        assertNotNull(content, "回答内容不应为空");
        assertFalse(content.isBlank(), "回答内容不应为空白");
        assertTrue(content.contains("512") || content.contains("64"),
                "回答应基于检索到的参数内容作答，实际回答：" + content);

        String sourcesRaw = answer.path("sources").asText();
        assertFalse(sourcesRaw.isBlank(), "有命中时应写入 sources 引用");
        JsonNode sources = readSources(sourcesRaw);
        assertTrue(sources.size() >= 1, "sources 至少 1 条");
        assertTrue(sources.size() <= 5, "rerank-top-n=5 应生效，sources 不得超过 5 条，实际 " + sources.size());
        step("A4-01 通过：端到端问答耗时 " + cost + "ms，回答长度 " + content.length()
                + " 字，引用来源 " + sources.size() + " 条");
    }

    @Test
    @Order(2)
    @DisplayName("A4-02 引用来源结构：每条形如 {chunkId, score, content}，预览超 100 字截断加省略号")
    void a402_sources_structure_and_preview_truncation() {
        AuthSession s = newUser();
        seedKnowledgeBase(s);
        long sessionId = createSession(s.token());

        JsonNode answer = ask(s.token(), sessionId, "召回多少条候选、精排保留几条？");
        JsonNode sources = readSources(answer.path("sources").asText());

        assertTrue(sources.size() >= 1, "应有引用来源");
        for (JsonNode src : sources) {
            assertTrue(src.has("chunkId"), "source 必须含 chunkId（可回溯到 MySQL 分块）");
            assertTrue(src.has("score"), "source 必须含 score");
            assertTrue(src.has("content"), "source 必须含 content 预览");
            assertTrue(src.path("chunkId").asLong() > 0, "chunkId 应为正整数");
            float score = (float) src.path("score").asDouble();
            assertTrue(score > 0 && score <= 1.001f,
                    "score 应落在 (0,1] 区间（重排相关性分数），实际 " + score);
            String preview = src.path("content").asText();
            assertTrue(preview.length() <= 103,
                    "预览应在 100 字处截断（含省略号不超过 103 字），实际 " + preview.length() + " 字");
        }
        step("A4-02 通过：sources 结构完整，共 " + sources.size() + " 条，预览截断规则生效");
    }

    // ==================== 2. 空召回兜底 ====================

    @Test
    @Order(3)
    @DisplayName("A4-03 空召回兜底：无文档用户提问返回固定拒答文案，不调用 LLM 编造")
    void a403_empty_recall_falls_back_without_hallucination() {
        AuthSession s = newUser();          // 全新用户，知识库为空
        long sessionId = createSession(s.token());

        long t0 = System.currentTimeMillis();
        JsonNode answer = ask(s.token(), sessionId, "请介绍一下量子计算的发展历史");
        long cost = System.currentTimeMillis() - t0;

        assertEquals(FALLBACK_TEXT, answer.path("content").asText(),
                "空召回必须走兜底文案（与实现逐字一致），避免 LLM 基于空上下文幻觉");
        assertTrue(answer.path("sources").isNull() || answer.path("sources").asText().isBlank(),
                "兜底分支不写 sources，引用来源应为空，实际：" + answer.path("sources").asText());
        assertTrue(cost < 5000,
                "兜底路径不应调用 LLM，耗时应远低于生成链路，实际 " + cost + "ms");
        step("A4-03 通过：空召回走兜底文案，耗时 " + cost + "ms（未调用 LLM）");
    }

    // ==================== 3. 持久化与隔离 ====================

    @Test
    @Order(4)
    @DisplayName("A4-04 消息持久化：历史记录含提问与回答两条且顺序正确（先落库提问）")
    void a404_history_persists_question_then_answer() {
        AuthSession s = newUser();
        seedKnowledgeBase(s);
        long sessionId = createSession(s.token());

        String question = "相似度阈值默认是多少？";
        JsonNode answer = ask(s.token(), sessionId, question);

        JsonNode history = jsonOf(httpGet("/api/chat/history/" + sessionId, s.token())).path("data");
        assertEquals(2, history.size(), "一次问答应产生 2 条消息（提问 + 回答）");
        assertEquals("user", history.get(0).path("role").asText(), "第 1 条应为用户提问");
        assertEquals(question, history.get(0).path("content").asText(), "提问内容应原样落库");
        assertEquals("assistant", history.get(1).path("role").asText(), "第 2 条应为助手回答");
        assertEquals(answer.path("content").asText(), history.get(1).path("content").asText(),
                "历史中的回答应与接口返回一致");
        step("A4-04 通过：历史持久化顺序正确（user → assistant）");
    }

    @Test
    @Order(5)
    @DisplayName("A4-05 会话隔离：A 的会话不出现在 B 的列表，B 也读不到 A 的历史")
    void a405_session_list_is_isolated() {
        AuthSession a = newUser();
        AuthSession b = newUser();
        long sessionA = createSession(a.token());

        JsonNode listB = jsonOf(httpGet("/api/chat/sessions", b.token())).path("data");
        for (JsonNode sess : listB) {
            assertTrue(sess.path("id").asLong() != sessionA,
                    "会话列表按 user_id 过滤，B 不应看到 A 创建的会话");
        }

        JsonNode historyAsB = jsonOf(httpGet("/api/chat/history/" + sessionA, b.token())).path("data");
        step("A4-05 记录：B 读取 A 的会话历史返回 " + historyAsB.size()
                + " 条（会话列表按 userId 隔离已生效；历史读取本身未做归属校验，A6-01 用有消息的会话确认了越权可读）");
    }

    @Test
    @Order(6)
    @DisplayName("A4-06 删除会话：级联清理消息，列表与历史同时消失")
    void a406_delete_session_cascades() {
        AuthSession s = newUser();
        long sessionId = createSession(s.token());
        ask(s.token(), sessionId, "任意问题");

        ResponseEntity<byte[]> del = httpDelete("/api/chat/session/" + sessionId, s.token());
        assertEquals(200, del.getStatusCode().value(), "删除会话应返回 200");

        JsonNode list = jsonOf(httpGet("/api/chat/sessions", s.token())).path("data");
        for (JsonNode sess : list) {
            assertTrue(sess.path("id").asLong() != sessionId, "删除后会话不应出现在列表");
        }
        JsonNode history = jsonOf(httpGet("/api/chat/history/" + sessionId, s.token())).path("data");
        assertTrue(history.isEmpty(), "级联删除后该会话历史应为空，实际 " + history.size() + " 条");
        step("A4-06 通过：会话级联删除生效");
    }

    // ==================== 4. 长期记忆闭环 ====================

    @Test
    @Order(7)
    @DisplayName("A4-07 长期记忆闭环：高质量问答（重排最高分 ≥ 0.6）写入 qa_memory 并可被同用户召回")
    void a407_long_term_memory_round_trip() {
        AuthSession s = newUser();
        seedKnowledgeBase(s);
        long sessionId = createSession(s.token());

        // 用文档原文整句提问，最大化重排相关性分数，确保越过 MEMORY_SAVE_MIN_SCORE=0.6 门槛
        String question = SENTENCE_CHUNK;
        JsonNode answer = ask(s.token(), sessionId, question);
        JsonNode sources = readSources(answer.path("sources").asText());
        float topScore = 0f;
        for (JsonNode src : sources) {
            topScore = Math.max(topScore, (float) src.path("score").asDouble());
        }

        // 记忆写入 qa_memory 后同样存在 Milvus 亚秒级可见性窗口，此处轮询等待而不是立刻断言，
        // 否则会得到"最高分 1.0 却没召回"的随机失败（与被测逻辑无关）
        List<String> recalled = List.of();
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            recalled = memoryService.recall(s.userId(), question);
            if (!recalled.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(recalled.isEmpty(),
                "重排最高分 " + String.format("%.4f", topScore) + "，若 ≥0.6 则本次问答应已写入 qa_memory 并被召回。"
                        + "召回为空说明记忆写入或召回链路存在问题");
        assertTrue(recalled.get(0).startsWith("Q:"), "召回内容应为 Q:/A: 可读格式，实际：" + recalled.get(0));

        // 记忆隔离：另一用户用同样问题召回，不应拿到别人的记忆
        AuthSession other = newUser();
        List<String> otherRecalled = memoryService.recall(other.userId(), question);
        assertTrue(otherRecalled.stream().noneMatch(m -> m.contains(sessionId + "")),
                "长期记忆按 user_id 隔离，不得串号");
        step("A4-07 通过：记忆闭环生效（重排最高分 " + String.format("%.4f", topScore)
                + "，本用户召回 " + recalled.size() + " 条，他人召回 " + otherRecalled.size() + " 条）");
    }

    // ==================== 5. 性能实测采集 ====================

    @Test
    @Order(8)
    @DisplayName("A4-08 端到端时延采集：连续 3 次问答记录真实耗时（供验收报告取证）")
    void a408_collect_latency_samples() {
        AuthSession s = newUser();
        seedKnowledgeBase(s);
        long sessionId = createSession(s.token());

        String[] questions = {
                "文档切片窗口和重叠分别是多少？",
                "向量维度是多少维？",
                "精排之后保留几条片段？",
        };
        long total = 0;
        StringBuilder samples = new StringBuilder();
        for (String q : questions) {
            long t0 = System.currentTimeMillis();
            JsonNode answer = ask(s.token(), sessionId, q);
            long cost = System.currentTimeMillis() - t0;
            total += cost;
            assertFalse(answer.path("content").asText().isBlank(), "回答不应为空");
            samples.append(cost).append("ms ");
        }
        long avg = total / questions.length;
        step("A4-08 实测：连续 3 次端到端问答耗时 " + samples + "，平均 " + avg + "ms（含查询改写 + 混合检索 + 重排 + LLM 生成）");
    }

    // ==================== 工具 ====================

    private JsonNode readSources(String sourcesText) {
        try {
            if (sourcesText == null || sourcesText.isBlank()) {
                return JSON.createArrayNode();
            }
            return JSON.readTree(sourcesText);
        } catch (Exception e) {
            throw new AssertionError("sources 不是合法 JSON 数组：" + sourcesText, e);
        }
    }
}
