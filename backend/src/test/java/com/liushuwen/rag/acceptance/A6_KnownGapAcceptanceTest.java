package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A6 —— 已知缺口固化验收（把"文档与实现的偏差"变成可回归的测试）。
 *
 * 设计意图：
 *   既有的项目文档中有一批表述与当前实现不符（越权防护、级联删除、增量重解析、
 *   缓存配置生效等）。这些偏差不影响主链路可用性，但会在面试追问时被拆穿。
 *   本类不放宽断言去"假装通过"，而是<b>精确断言当前真实行为</b>：
 *     - 一旦实现被修好，这些用例会立刻失败 → 强制同步更新文档，避免文档再次漂移；
 *     - 在当前状态下，它们构成一份"已知缺口清单"的事实依据。
 *
 *   说明：原先本类清单里的「熔断覆盖范围」缺口（熔断只挂 AgentExecutor、主链路裸奔）已在
 *   后续迭代中修复——熔断收口到全站 LLM 唯一出口 LlmService，覆盖范围由 A5-02 / A5-12 正面取证，
 *   故不再是缺口，也不在本类中固化。
 *
 * 这些用例标记为 @Order 靠后，且不计入 P0 通过门槛（报告单列）。
 * 通过标准：全部断言"当前行为"成立（即测试通过即代表缺口清单准确）。
 */
@DisplayName("A6 已知缺口固化验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A6_KnownGapAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private MilvusService milvusService;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private RagProperties ragProperties;

    /** 直接查库统计分块残留：比依赖"检索排序"这类非确定信号更可靠 */
    @Autowired
    private com.liushuwen.rag.document.mapper.DocumentChunkMapper chunkMapper;

    // ==================== 缺口①：会话归属校验缺失 ====================

    @Test
    @Order(1)
    @DisplayName("A6-01【缺口①】会话归属未校验：B 的 token 可读到 A 的会话历史")
    void a601_session_ownership_not_enforced_on_read() {
        AuthSession a = newUser();
        AuthSession b = newUser();
        long sessionA = createSession(a.token());
        ask(a.token(), sessionA, "这是 A 的私密提问");

        JsonNode historyAsB = jsonOf(httpGet("/api/chat/history/" + sessionA, b.token())).path("data");

        assertEquals(200,
                jsonOf(httpGet("/api/chat/history/" + sessionA, b.token())).path("code").asInt(),
                "接口本身返回成功");
        assertTrue(historyAsB.size() > 0,
                "【缺口】ChatServiceImpl#getHistory 只按 sessionId 过滤，未校验该会话是否属于当前用户，"
                        + "B 用自己合法 token 即可读到 A 的会话内容。当前实测读到 " + historyAsB.size() + " 条。"
                        + "文档中「Service 内做归属校验防越权」的表述与实现不符。");
        boolean leaked = false;
        for (JsonNode m : historyAsB) {
            if (m.path("content").asText().contains("A 的私密提问")) {
                leaked = true;
            }
        }
        assertTrue(leaked, "确实发生了跨用户内容泄露（读到 A 的提问原文）");
        step("A6-01 记录：跨用户读取会话历史成功，读到 " + historyAsB.size() + " 条（越权缺口确认）");
    }

    @Test
    @Order(2)
    @DisplayName("A6-02【缺口①】会话归属未校验：B 的 token 可删除 A 的会话")
    void a602_session_ownership_not_enforced_on_delete() {
        AuthSession a = newUser();
        AuthSession b = newUser();
        long sessionA = createSession(a.token());

        ResponseEntity<byte[]> del = httpDelete("/api/chat/session/" + sessionA, b.token());
        assertEquals(200, del.getStatusCode().value(), "接口返回成功");

        JsonNode stillThere = jsonOf(httpGet("/api/chat/history/" + sessionA, a.token())).path("data");
        assertTrue(stillThere.isEmpty(),
                "【缺口】deleteSession 同样未校验归属，B 已成功删除 A 的会话（A 的历史已为空，"
                        + "实际剩余 " + stillThere.size() + " 条）");
        step("A6-02 记录：跨用户删除会话成功（越权缺口确认）");
    }

    // ==================== 缺口②：删除文档不级联清理向量 ====================

    @Test
    @Order(3)
    @DisplayName("A6-03【缺口②】删除文档只做 MySQL 逻辑删除：分块行与 Milvus 向量均残留")
    void a603_delete_document_leaves_orphan_vectors() {
        AuthSession s = newUser();
        String marker = "ORPHAN" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JsonNode doc = uploadDoc(s.token(), "将被删除.md",
                "# 孤儿向量验证\n\n本文档唯一标记词是 " + marker + "，删除后其向量是否仍可被召回？\n"
                        + "补充内容用于凑足分块长度，确保产生可检索的向量。\n", "其他");
        long docId = doc.path("id").asLong();
        embedDoc(s.token(), docId);

        // 前置：向量可被检索（先等写入可见：Milvus Bounded 一致性存在亚秒级窗口）
        float[] qv = embeddingService.embedSingle("本文档唯一标记词是 " + marker);
        assertTrue(waitUntilRetrievable(milvusService, qv, docId, marker), "前置条件：向量化后 15s 内仍不可检索");
        long chunksBefore = countChunks(docId);
        assertTrue(chunksBefore > 0, "前置条件：文档解析后应有分块，实际 " + chunksBefore);

        // 删除文档（仅 MySQL 逻辑删除）
        assertEquals(200, httpDelete("/api/document/" + docId, s.token()).getStatusCode().value());

        // ① document 行确已逻辑删除：不再出现在文档列表
        boolean stillListed = false;
        for (JsonNode d : jsonOf(httpGet("/api/document/list", s.token())).path("data")) {
            if (d.path("id").asLong() == docId) {
                stillListed = true;
            }
        }
        assertFalse(stillListed, "document 行应已逻辑删除（列表不可见）");

        // ② document_chunk 分块行残留（直接查库，避免依赖"检索排序"这类非确定信号）
        long chunksAfter = countChunks(docId);
        assertEquals(chunksBefore, chunksAfter,
                "【缺口】DocumentServiceImpl#delete 只做 documentMapper.deleteById，"
                        + "未清理 document_chunk，删除后仍残留 " + chunksAfter + " 行分块。"
                        + "同理 MinIO 原文件对象也未清理。");

        // ③ Milvus 向量残留：Milvus 不知道 MySQL 的逻辑删除，按已删文档的 document_id 仍可直接召回
        List<MilvusService.SearchResult> orphan = milvusService.search(qv, 5, List.of(docId));
        assertTrue(orphan.stream().anyMatch(h -> h.getContent() != null && h.getContent().contains(marker)),
                "【缺口】delete() 未调用 milvusService.deleteByDocumentId，"
                        + "向量行仍存在且可按 document_id 召回（对比：reparseDocument 里是有调用该方法的）");

        // ④ 正式链路为何看不到脏数据：documentIds 取自 MySQL（deleted=0 且已向量化），已删文档不在其中，
        //    故主问答链路与 A3 的隔离用例都不受影响 —— 这正是缺口长期未被发现的原因。
        step("A6-03 记录：删除文档后 document_chunk 残留 " + chunksAfter + " 行、Milvus 向量仍可按 document_id 召回；"
                + "正式链路因 documentIds 过滤而不可见，掩盖了该缺口。"
                + "即文档中「级联清理 MinIO/MySQL/Milvus」的表述三项都未落地");
    }

    /** 统计某文档在 MySQL 中的分块行数 */
    private long countChunks(long documentId) {
        return chunkMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper
                <com.liushuwen.rag.document.entity.DocumentChunk>()
                .eq(com.liushuwen.rag.document.entity.DocumentChunk::getDocumentId, documentId));
    }

    // ==================== 缺口③：增量重解析无 HTTP 入口 ====================

    @Test
    @Order(4)
    @DisplayName("A6-04【缺口③】reparseDocument 已实现但无 Controller 暴露，HTTP 层不可达")
    void a604_reparse_has_no_http_endpoint() throws Exception {
        Class<?> controller = Class.forName("com.liushuwen.rag.document.controller.DocumentController");
        List<String> mappings = new ArrayList<>();
        String base = controller.getAnnotation(RequestMapping.class) != null
                ? controller.getAnnotation(RequestMapping.class).value()[0] : "";
        for (Method m : controller.getDeclaredMethods()) {
            String path = null;
            if (m.isAnnotationPresent(PostMapping.class)) path = Arrays.toString(m.getAnnotation(PostMapping.class).value());
            else if (m.isAnnotationPresent(GetMapping.class)) path = Arrays.toString(m.getAnnotation(GetMapping.class).value());
            else if (m.isAnnotationPresent(DeleteMapping.class)) path = Arrays.toString(m.getAnnotation(DeleteMapping.class).value());
            else if (m.isAnnotationPresent(PutMapping.class)) path = Arrays.toString(m.getAnnotation(PutMapping.class).value());
            else if (m.isAnnotationPresent(PatchMapping.class)) path = Arrays.toString(m.getAnnotation(PatchMapping.class).value());
            if (path != null) mappings.add(base + path);
        }

        // 实现侧确实有这个方法
        Class<?> serviceImpl = Class.forName("com.liushuwen.rag.document.service.impl.DocumentServiceImpl");
        assertNotNull(serviceImpl.getMethod("reparseDocument", Long.class), "实现侧存在 reparseDocument");

        // 但 HTTP 层没有任何端点指向它
        assertTrue(mappings.stream().noneMatch(m -> m.contains("reparse")),
                "【缺口】DocumentController 未暴露 reparseDocument，文档中「增量重解析」属不可达能力。"
                        + "当前 DocumentController 全部端点：" + mappings);
        step("A6-04 记录：DocumentController 端点共 " + mappings.size() + " 个 → " + mappings);
    }

    // ==================== 缺口④：鉴权失败返回 400，前端按 401 处理 ====================

    @Test
    @Order(5)
    @DisplayName("A6-05【缺口④】鉴权失败统一返回 HTTP 400，而前端 axios 只对 401 做登出跳转")
    void a605_auth_failure_returns_400_not_401() {
        AuthSession s = newUser();
        String expired = s.token().substring(0, s.token().length() - 3) + "xyz";

        ResponseEntity<byte[]> resp = httpGet("/api/chat/sessions", expired);

        assertTrue(resp.getStatusCode().value() != 401,
                "【缺口】JwtInterceptor 抛 BusinessException，经 GlobalExceptionHandler 统一转为 HTTP 400，"
                        + "而 frontend/src/api/index.js 的响应拦截器只在 status===401 时清除 token 并跳登录页，"
                        + "该分支为死代码。实际返回：" + resp.getStatusCode().value());
        assertEquals(400, resp.getStatusCode().value(), "当前实际返回 400");
        step("A6-05 记录：鉴权失败返回 HTTP 400（前端 401 分支不可达，token 过期不会自动跳登录页）");
    }

    // ==================== 缺口⑤：会话标题请求体契约不一致 ====================

    @Test
    @Order(6)
    @DisplayName("A6-06【缺口⑤】会话标题：前端发 JSON 字符串、后端收裸 String，需观察引号是否入库")
    void a606_session_title_body_contract() {
        AuthSession s = newUser();
        long sessionId = createSession(s.token());
        String title = "验收标题" + UUID.randomUUID().toString().substring(0, 4);

        // 复刻前端 chat.js 的调用：request.put(url, JSON.stringify(title)) → body 是 "\"验收标题xxxx\""
        ResponseEntity<byte[]> resp = httpPutJson("/api/chat/session/" + sessionId + "/title",
                "\"" + title + "\"", s.token());
        assertEquals(200, resp.getStatusCode().value(), "更新标题接口应返回成功：" + bodyOf(resp));

        String stored = null;
        for (JsonNode sess : jsonOf(httpGet("/api/chat/sessions", s.token())).path("data")) {
            if (sess.path("id").asLong() == sessionId) {
                stored = sess.path("title").asText();
            }
        }
        assertNotNull(stored, "应能在会话列表中找到该会话");
        assertTrue(stored.contains(title), "标题应包含原标题文本");
        step("A6-06 记录：前端发送 [\"" + title + "\"]，实际入库标题为 [" + stored + "]"
                + (stored.startsWith("\"") ? " —— 双引号被一并存入，属前端契约不严谨" : " —— 未带引号，契约可用但风格不一致"));
    }

    // ==================== 缺口⑥：缓存上限配置未接线 ====================

    @Test
    @Order(7)
    @DisplayName("A6-07【缺口⑥】rag.retrieval.embed-cache-limit 已配置但 EmbeddingService 未注入配置组")
    void a607_embedding_cache_limit_not_wired() {
        assertEquals(5000, ragProperties.getRetrieval().getEmbedCacheLimit(),
                "yml 中 rag.retrieval.embed-cache-limit 已绑定到 RagProperties（=5000）");

        boolean usesConfig = false;
        for (Field f : EmbeddingService.class.getDeclaredFields()) {
            if (f.getType().equals(RagProperties.class)) {
                usesConfig = true;
            }
        }
        assertFalse(usesConfig,
                "【缺口】EmbeddingService 未注入 RagProperties，其缓存上限来自硬编码常量 CACHE_LIMIT=5000，"
                        + "因此改 yml 的 embed-cache-limit 不会生效（配置项实为装饰性）。");
        step("A6-07 记录：embed-cache-limit 配置未接线，缓存上限实为 EmbeddingService 内硬编码常量 5000");
    }
}
