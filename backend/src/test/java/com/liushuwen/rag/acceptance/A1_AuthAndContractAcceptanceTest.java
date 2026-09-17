package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1 —— 鉴权与对外契约验收（HTTP 层）。
 *
 * 覆盖范围：
 *   1. 注册 / 登录 / 当前用户三个认证接口的响应契约与 JWT 下发
 *   2. JWT 拦截器的放行与拦截边界（白名单精确性）
 *   3. 统一响应体 Result{code,message,data} 与 GlobalExceptionHandler 的 HTTP 状态码约定
 *   4. 【重点】问答接口的请求体契约 —— 对象 {@code {"question":"..."}} 可用、裸 JSON 串不可用
 *
 * 为什么把"请求体契约"放进鉴权/契约层而不是问答层：
 *   9 月修复的缺陷①（前端发裸 JSON 串、后端收 DTO，导致真实点击问答必 500）在既有的
 *   检索评估测试里完全没有射程——评估测试直接调 Service，不经过 HTTP。
 *   该缺陷属于"对外契约"问题，因此在此层固化正反两个用例，形成防回退网。
 *
 * 通过标准（P0，全部必须通过）：
 *   - 所有认证接口成功时 HTTP 200 且 body.code == 200
 *   - 业务异常（BusinessException）经全局异常处理器统一转 HTTP 400 且 body.code == 400
 *   - 未鉴权请求被拦截器拒绝，不得返回 200
 *   - 问答接口：对象请求体 → 200；裸 JSON 串请求体 → 非 200
 */
@DisplayName("A1 鉴权与对外契约验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A1_AuthAndContractAcceptanceTest extends AcceptanceSupport {

    // ==================== 1. 认证接口契约 ====================

    @Test
    @Order(1)
    @DisplayName("A1-01 注册：合法账号返回 200 且落库自增 id")
    void a101_register_success() {
        String username = TEST_USER_PREFIX + System.currentTimeMillis() + "_r";
        String json = "{\"username\":\"" + username + "\",\"password\":\"" + TEST_PASSWORD + "\"}";

        ResponseEntity<byte[]> resp = httpPostJson("/api/auth/register", json, null);

        assertEquals(200, resp.getStatusCode().value(), "注册应返回 HTTP 200");
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(), "注册业务码应为 200");
        assertTrue(node.path("data").path("id").asLong() > 0, "注册应回填自增 id");
        assertEquals(username, node.path("data").path("username").asText(), "返回的用户名应与入参一致");
        step("A1-01 通过：注册账号 " + username + " id=" + node.path("data").path("id").asLong());
    }

    @Test
    @Order(2)
    @DisplayName("A1-02 注册重名：抛业务异常并统一转 HTTP 400 / code 400")
    void a102_register_duplicate_rejected() {
        AuthSession session = newUser();
        String json = "{\"username\":\"" + session.username() + "\",\"password\":\"" + TEST_PASSWORD + "\"}";

        ResponseEntity<byte[]> resp = httpPostJson("/api/auth/register", json, null);

        assertEquals(400, resp.getStatusCode().value(),
                "重名注册应由 GlobalExceptionHandler 转为 HTTP 400，实际：" + resp.getStatusCode());
        JsonNode node = jsonOf(resp);
        assertEquals(400, node.path("code").asInt(), "业务码应为 400");
        assertEquals("用户名已存在", node.path("message").asText(), "错误信息应与实现一致");
        step("A1-02 通过：重名校验生效（HTTP 400 / code 400 / 用户名已存在）");
    }

    @Test
    @Order(3)
    @DisplayName("A1-03 登录：返回 token 与用户信息，且密码字段必须为 null（敏感信息不回传）")
    void a103_login_returns_token_and_masks_password() {
        AuthSession session = newUser();
        String json = "{\"username\":\"" + session.username() + "\",\"password\":\"" + TEST_PASSWORD + "\"}";

        ResponseEntity<byte[]> resp = httpPostJson("/api/auth/login", json, null);
        JsonNode node = jsonOf(resp);
        JsonNode user = node.path("data").path("user");

        assertEquals(200, node.path("code").asInt());
        assertTrue(node.path("data").path("token").asText().length() > 20, "应下发 JWT");
        assertEquals(session.username(), user.path("username").asText());
        assertNull(user.path("password").isMissingNode() ? null : user.path("password").asText(null),
                "密码属敏感信息，必须不回传（AuthController 显式 setPassword(null)）");
        step("A1-03 通过：token 已下发，password 字段为 null");
    }

    @Test
    @Order(4)
    @DisplayName("A1-04 登录失败：用户不存在与密码错误返回同一提示（防用户名枚举）")
    void a104_login_failure_message_is_uniform() {
        ResponseEntity<byte[]> notExist = httpPostJson("/api/auth/login",
                "{\"username\":\"no_such_user_9999\",\"password\":\"x\"}", null);
        AuthSession session = newUser();
        ResponseEntity<byte[]> wrongPwd = httpPostJson("/api/auth/login",
                "{\"username\":\"" + session.username() + "\",\"password\":\"wrong_password\"}", null);

        assertEquals(400, notExist.getStatusCode().value(), "登录失败应返回 HTTP 400");
        assertEquals(400, wrongPwd.getStatusCode().value(), "密码错误应返回 HTTP 400");
        String m1 = jsonOf(notExist).path("message").asText();
        String m2 = jsonOf(wrongPwd).path("message").asText();
        assertEquals("用户名或密码错误", m1);
        assertEquals(m1, m2, "两种失败必须返回相同文案，否则可枚举有效账号");
        step("A1-04 通过：失败文案统一为「用户名或密码错误」");
    }

    // ==================== 2. JWT 拦截器边界 ====================

    @Test
    @Order(5)
    @DisplayName("A1-05 无 token 访问受保护接口：被拦截器拒绝（不得返回 200）")
    void a105_missing_token_rejected() {
        ResponseEntity<byte[]> resp = httpGet("/api/auth/me", null);

        assertEquals(400, resp.getStatusCode().value(),
                "无 token 应被 JwtInterceptor 拦截并转 HTTP 400，实际：" + resp.getStatusCode());
        JsonNode node = jsonOf(resp);
        assertTrue(node.path("code").asInt() != 200, "无 token 不得返回业务成功码");
        assertTrue(node.path("message").asText().contains("认证令牌"),
                "错误信息应说明缺少令牌，实际：" + node.path("message").asText());
        step("A1-05 通过：无 token → HTTP 400 / code " + node.path("code").asInt()
                + " / " + node.path("message").asText());
    }

    @Test
    @Order(6)
    @DisplayName("A1-06 带合法 token：/api/auth/me 返回当前用户（白名单未误放行）")
    void a106_valid_token_returns_current_user() {
        AuthSession session = newUser();

        ResponseEntity<byte[]> resp = httpGet("/api/auth/me", session.token());
        JsonNode node = jsonOf(resp);

        assertEquals(200, resp.getStatusCode().value(), "带 token 应放行：" + bodyOf(resp));
        assertEquals(200, node.path("code").asInt());
        assertEquals(session.username(), node.path("data").path("username").asText(),
                "/api/auth/me 不在白名单内，必须能拿到 userId 对应的用户");
        step("A1-06 通过：/api/auth/me 正确返回当前登录用户 " + session.username());
    }

    @Test
    @Order(7)
    @DisplayName("A1-07 篡改 token：签名校验失败被拒（不得返回 200）")
    void a107_tampered_token_rejected() {
        AuthSession session = newUser();
        String tampered = session.token().substring(0, session.token().length() - 4) + "AAAA";

        ResponseEntity<byte[]> resp = httpGet("/api/chat/sessions", tampered);

        assertTrue(resp.getStatusCode().value() >= 400,
                "篡改签名必须被拒绝，实际：" + resp.getStatusCode());
        assertTrue(jsonOf(resp).path("code").asInt() != 200, "篡改 token 不得返回成功码");
        step("A1-07 通过：篡改 token → HTTP " + resp.getStatusCode().value());
    }

    @Test
    @Order(8)
    @DisplayName("A1-08 未登录访问问答类接口：全部拒绝（鉴权覆盖 /api/**）")
    void a108_protected_endpoints_require_auth() {
        String[][] endpoints = {
                {"/api/chat/sessions", "GET"},
                {"/api/document/list", "GET"},
                {"/api/metrics/today", "GET"},
        };
        for (String[] ep : endpoints) {
            ResponseEntity<byte[]> resp = httpGet(ep[0], null);
            assertTrue(resp.getStatusCode().value() >= 400,
                    ep[0] + " 未登录不得放行，实际：" + resp.getStatusCode());
            assertTrue(jsonOf(resp).path("code").asInt() != 200, ep[0] + " 未登录不得返回成功码");
        }
        step("A1-08 通过：3 个受保护接口在无 token 时全部拒绝");
    }

    // ==================== 3. 统一响应体与异常码 ====================

    @Test
    @Order(9)
    @DisplayName("A1-09 统一响应体：成功响应必含 code/message/data 三字段")
    void a109_unified_result_shape() {
        AuthSession session = newUser();
        JsonNode node = jsonOf(httpGet("/api/document/list", session.token()));

        assertTrue(node.has("code"), "统一响应体必须含 code");
        assertTrue(node.has("message"), "统一响应体必须含 message");
        assertTrue(node.has("data"), "统一响应体必须含 data");
        assertEquals(200, node.path("code").asInt());
        assertEquals("success", node.path("message").asText());
        assertTrue(node.path("data").isArray(), "文档列表 data 应为数组");
        step("A1-09 通过：Result 统一响应体结构正确（code/message/data）");
    }

    // ==================== 4. 问答请求体契约（缺陷① 防回退网） ====================

    @Test
    @Order(10)
    @DisplayName("A1-10 【契约回归】问答发对象请求体 {\"question\":\"...\"}：必须成功")
    void a110_ask_accepts_object_body() {
        AuthSession session = newUser();
        long sessionId = createSession(session.token());

        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "{\"question\":\"你好\"}", session.token());
        JsonNode node = jsonOf(resp);

        assertEquals(200, resp.getStatusCode().value(),
                "对象请求体必须被 AskRequest 正确反序列化，实际 HTTP " + resp.getStatusCode()
                        + "：" + bodyOf(resp));
        assertEquals(200, node.path("code").asInt(), "业务码应为 200：" + bodyOf(resp));
        assertEquals("assistant", node.path("data").path("role").asText(), "应返回助手消息");
        assertNotNull(node.path("data").path("content").asText(null), "回答内容不应为空");
        step("A1-10 通过：对象请求体问答成功（前端 chat.js 修复后的契约）");
    }

    @Test
    @Order(11)
    @DisplayName("A1-11 【契约回归·反向】问答发裸 JSON 字符串：必须被拒且判为 400（客户端错误而非 500）")
    void a111_ask_rejects_bare_json_string_body() {
        AuthSession session = newUser();
        long sessionId = createSession(session.token());

        // 这正是修复前 frontend/src/api/chat.js 的行为：JSON.stringify(question) → "\"问题\""
        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId,
                "\"系统的向量维度是多少\"", session.token());
        JsonNode node = jsonOf(resp);

        assertTrue(resp.getStatusCode().value() >= 400,
                "裸 JSON 串无法绑定 AskRequest，必须失败；若此处返回 200，说明后端又改回了 "
                        + "@RequestBody String，前端契约将再次错位。实际 HTTP " + resp.getStatusCode());
        assertTrue(node.path("code").asInt() != 200,
                "裸 JSON 串不得返回成功码，实际：" + bodyOf(resp));
        // 缺陷③验收：修复前该请求落到 catch-all 返回 HTTP 500（把客户端格式错误误报为服务端故障）。
        // 修复后由 GlobalExceptionHandler 的 HttpMessageNotReadableException 分支判定为 400。
        assertEquals(400, resp.getStatusCode().value(),
                "请求体解析失败属客户端错误，应为 HTTP 400；若为 500 说明 GlobalExceptionHandler "
                        + "缺少 HttpMessageNotReadableException 分支。实际：" + resp.getStatusCode());
        assertEquals(400, node.path("code").asInt(), "业务码应为 400，实际：" + bodyOf(resp));
        step("A1-11 通过：裸 JSON 串被拒（HTTP 400 / code 400），契约边界已固化");
    }

    @Test
    @Order(12)
    @DisplayName("A1-12 参数校验：空问题 / 纯空白 / 缺字段均被拒，且 HTTP 语义与业务异常路径一致")
    void a112_blank_question_rejected() {
        AuthSession session = newUser();
        long sessionId = createSession(session.token());

        String[][] cases = {
                {"{\"question\":\"\"}", "空字符串"},
                {"{\"question\":\"   \"}", "纯空白"},
                {"{}", "缺 question 字段"},
        };
        for (String[] c : cases) {
            ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId, c[0], session.token());
            JsonNode node = jsonOf(resp);
            assertTrue(node.path("code").asInt() != 200,
                    c[1] + " 必须被拒绝，实际：" + bodyOf(resp));
            assertEquals("问题不能为空", node.path("message").asText(),
                    c[1] + " 的错误信息应与 ChatController 校验分支一致");
            // 【补充断言·HTTP 语义双轨】原用例只断言响应体 code，未断言 HTTP 状态码，
            // 于是「用 return Result.error(400, ...) 做内联校验 → HTTP 200」这种缺陷可以全绿存活。
            // 校验失败必须与 Service 抛 BusinessException 的路径保持同一套 HTTP 语义（都是 400），
            // 否则按 HTTP 判断的调用方（网关、监控、第三方集成）会把失败当成功。
            assertEquals(400, resp.getStatusCode().value(),
                    c[1] + " 的 HTTP 状态码必须为 400（与 BusinessException 路径一致）；"
                            + "若为 200 说明校验走了 return Result.error(...) 的内联分支。实际："
                            + resp.getStatusCode() + "，" + bodyOf(resp));
        }
        step("A1-12 通过：空 / 空白 / 缺字段 三类非法提问均 HTTP 400 + 「问题不能为空」");
    }

    @Test
    @Order(13)
    @DisplayName("A1-13 不存在路径：未匹配到资源/处理器时返回 404，而非被兜底吞成 500")
    void a113_unknown_path_returns_404() {
        // 【缺陷修复·G-05】修复前 GlobalExceptionHandler 的 catch-all（@ExceptionHandler(Exception.class)）
        // 把 Spring 的 NoResourceFoundException（未匹配到静态资源 / 处理器）一并吞掉，返回 HTTP 500
        // 「系统内部错误」——把「客户端请求了不存在的路径」误报成「服务端故障」。
        // 副作用：Knife4j（/doc.html）加载 /favicon.ico 时前端控制台持续刷 500，掩盖真实异常。
        // REST 语义上，未匹配路径应为 404（客户端错误），此处把该约定固化，防止回退。
        String[] paths = {"/favicon.ico", "/no-such-page-" + System.currentTimeMillis()};
        for (String p : paths) {
            ResponseEntity<byte[]> resp = httpGet(p, null);
            assertEquals(404, resp.getStatusCode().value(),
                    p + " 应返回 HTTP 404（未匹配到资源/处理器）；若为 500 说明 GlobalExceptionHandler "
                            + "缺少 NoResourceFoundException 分支。实际：" + resp.getStatusCode()
                            + "，" + bodyOf(resp));
            JsonNode node = jsonOf(resp);
            assertEquals(404, node.path("code").asInt(),
                    p + " 的业务码应与 HTTP 状态码一致为 404，实际：" + bodyOf(resp));
        }
        step("A1-13 通过：未知路径（/favicon.ico 与随机拼错路径）均返回 HTTP 404 / code 404");
    }
}
