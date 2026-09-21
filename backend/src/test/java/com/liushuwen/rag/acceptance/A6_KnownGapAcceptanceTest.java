package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.config.RagProperties;
import com.liushuwen.rag.document.service.EmbeddingService;
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
 *   既有的项目文档中有一批表述与当前实现不符（增量重解析、缓存配置生效等）。这些偏差不影响主链路可用性，但会在面试追问时被拆穿。
 *   本类不放宽断言去"假装通过"，而是<b>精确断言当前真实行为</b>：
 *     - 一旦实现被修好，这些用例会立刻失败 → 强制同步更新文档，避免文档再次漂移；
 *     - 在当前状态下，它们构成一份"已知缺口清单"的事实依据。
 *
 *   说明：原先本类清单里的「熔断覆盖范围」缺口（熔断只挂 AgentExecutor、主链路裸奔）已在
 *   后续迭代中修复——熔断收口到全站 LLM 唯一出口 LlmService，覆盖范围由 A5-02 / A5-12 正面取证，
 *   故不再是缺口，也不在本类中固化。
 *
 *   同样地，「删除文档不级联清理向量」缺口（原 A6-03）也已在结构治理中修复：delete() 现按
 *   Milvus 向量 → MinIO 对象 → MySQL 分块 → 文档行 的顺序级联清理，并加 @Transactional 与归属校验，
 *   其正向取证改由 A2-14 / A2-15 承担，故本类不再固化该缺口。
 *
 *   「会话归属校验缺失」缺口（原 A6-01 / A6-02）同样已修复：会话的按 id 读写（读历史 / 删除 / 改标题）
 *   全部收口到 ChatSessionService 的归属校验，正向取证改由 A4-10 / A4-11 承担。
 *
 *   注意：本类的用例编号沿用原始台账编号（缺口③④⑤⑥），**刻意不重排**——
 *   编号是历史报告的追溯锚点，缺口①②修好后留空反而更容易对上旧文档。
 *
 * 这些用例标记为 @Order 靠后，且不计入 P0 通过门槛（报告单列）。
 * 通过标准：全部断言"当前行为"成立（即测试通过即代表缺口清单准确）。
 */
@DisplayName("A6 已知缺口固化验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A6_KnownGapAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private RagProperties ragProperties;

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
