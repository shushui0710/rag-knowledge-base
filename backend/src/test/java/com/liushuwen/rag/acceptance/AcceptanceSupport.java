package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.xml.SourceHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收测试基类：真实 HTTP 客户端 + 测试账号/文档工厂。
 *
 * 【为什么用 RANDOM_PORT 而不是 MockMvc】
 * MockMvc 绕过 Servlet 容器与拦截器注册链路，测不到 JWT 拦截器、CORS、multipart 解析与
 * @RestControllerAdvice 的真实 HTTP 状态码。验收的目的是"证明对外契约成立"，因此必须走真实网络栈。
 *
 * 【为什么请求/响应都用 byte[] 而不是 String】
 * StringHttpMessageConverter 的默认字符集在不同 Spring 版本间不一致，直接用 String 收发中文会出现
 * 隐式转码。统一用 UTF-8 编码的 byte[] + 显式 charset，保证"线上字节 == 期望字节"，
 * 这样才敢断言裸 JSON 串、中文内容这类对编码敏感的契约。
 *
 * 【浏览器代理规避】
 * 本机存在 Clash 等本地代理时，HttpURLConnection 可能被系统代理属性劫持导致 localhost 访问失败；
 * pom 的 surefire argLine 已设置 -Djava.net.useSystemProxies=false 兜底。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AcceptanceSupport {

    @LocalServerPort
    protected int port;

    protected RestTemplate http;

    protected static final ObjectMapper JSON = new ObjectMapper();

    /** 验收用测试账号统一前缀，便于用 SQL 批量清理，不影响 admin / 演示账号 */
    public static final String TEST_USER_PREFIX = "accept_";

    /** 测试账号统一密码 */
    public static final String TEST_PASSWORD = "Accept123456";

    /**
     * 环境指纹：验收报告的第一条证据。
     *
     * 为什么必须固定打印：本机 JDK 17 在中文 Windows 上默认 file.encoding=GBK，会让中文经
     * Milvus BM25 分词后触发 tantivy Rust panic；同时若 JVM 误用了系统代理（Clash 等会做 TLS MITM），
     * 调用 DeepSeek 会报 PKIX 证书链失败。这两类环境问题都会让"链路看起来是代码 bug"，
     * 因此每轮验收先固化环境事实，便于归因与复现。
     */
    @org.junit.jupiter.api.BeforeAll
    static void printEnvironmentFingerprint() {
        System.out.println("================ 验收环境指纹 ================");
        System.out.println("java.version            = " + System.getProperty("java.version"));
        System.out.println("file.encoding           = " + System.getProperty("file.encoding"));
        System.out.println("sun.jnu.encoding        = " + System.getProperty("sun.jnu.encoding"));
        System.out.println("java.net.useSystemProxies = " + System.getProperty("java.net.useSystemProxies"));
        System.out.println("http.proxyHost          = " + System.getProperty("http.proxyHost"));
        try {
            System.out.println("deepseek 选定路由        = " + java.net.ProxySelector.getDefault()
                    .select(new java.net.URI("https://api.deepseek.com")));
        } catch (Exception ignored) {
        }
        System.out.println("==============================================");
    }

    @BeforeEach
    void initHttpClient() {
        RestTemplate rt = new RestTemplate();
        List<org.springframework.http.converter.HttpMessageConverter<?>> converters = new ArrayList<>();
        // 只用字节/表单转换器：完全控制编码，避免隐式字符集转换掩盖契约问题
        converters.add(new ByteArrayHttpMessageConverter());
        converters.add(new FormHttpMessageConverter());
        rt.setMessageConverters(converters);

        // 【关键】禁用 RestTemplate 的默认错误抛出：DefaultResponseErrorHandler 遇到 4xx/5xx 会抛
        // HttpClientErrorException/HttpServerErrorException，导致我们根本拿不到 ResponseEntity 的
        // 真实状态码，"断言 400 / 断言非 200" 的负向用例会全部变成测试框架异常（假失败）。
        // 验收要的就是"观察服务端的真实状态码"，因此把 hasError 一律置为 false，让响应原样返回。
        rt.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(org.springframework.http.client.ClientHttpResponse response) {
                // 不需要默认的异常转换，响应体由调用方自行断言
            }
        });
        this.http = rt;
    }

    // ==================== 基础 HTTP ====================

    protected String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** GET，返回原始响应（字节级，不解码），便于断言精确的状态码与 JSON 内容 */
    protected ResponseEntity<byte[]> httpGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        return http.exchange(baseUrl() + path, HttpMethod.GET,
                new HttpEntity<>(null, headers), byte[].class);
    }

    protected ResponseEntity<byte[]> httpPostRaw(String path, byte[] body, String contentType, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (contentType != null) {
            headers.set("Content-Type", contentType);
        }
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        return http.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
    }

    protected ResponseEntity<byte[]> httpPostJson(String path, String jsonBody, String token) {
        // 允许 jsonBody 为 null：/api/document/embed/{id} 等接口无请求体，
        // 早期版本在此处直接 getBytes 会 NPE，故统一降级为空字节数组。
        byte[] payload = jsonBody == null ? new byte[0] : jsonBody.getBytes(StandardCharsets.UTF_8);
        return httpPostRaw(path, payload, "application/json;charset=UTF-8", token);
    }

    protected ResponseEntity<byte[]> httpPutJson(String path, String jsonBody, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json;charset=UTF-8");
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        return http.exchange(baseUrl() + path, HttpMethod.PUT,
                new HttpEntity<>(jsonBody.getBytes(StandardCharsets.UTF_8), headers), byte[].class);
    }

    protected ResponseEntity<byte[]> httpDelete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        return http.exchange(baseUrl() + path, HttpMethod.DELETE,
                new HttpEntity<>(null, headers), byte[].class);
    }

    /** multipart 上传，模拟浏览器 el-upload 行为 */
    protected ResponseEntity<byte[]> httpUpload(String path, String filename, byte[] content,
                                                String category, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource res = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        form.add("file", res);
        if (category != null) {
            form.add("category", category);
        }
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        form.add("_", new HttpEntity<>("", partHeaders));
        return http.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(form, headers), byte[].class);
    }

    // ==================== 响应解析 ====================

    protected String bodyOf(ResponseEntity<byte[]> resp) {
        return new String(resp.getBody() == null ? new byte[0] : resp.getBody(), StandardCharsets.UTF_8);
    }

    protected JsonNode jsonOf(ResponseEntity<byte[]> resp) {
        try {
            return JSON.readTree(bodyOf(resp));
        } catch (Exception e) {
            throw new AssertionError("响应不是合法 JSON：" + bodyOf(resp), e);
        }
    }

    // ==================== 业务便捷方法 ====================

    /** 验收账号会话：用户名 + JWT + userId */
    public record AuthSession(String username, String token, long userId) {
    }

    /** 注册并登录一个全新的验收账号（用户名带时间戳后缀，保证可重复运行不冲突） */
    protected AuthSession newUser() {
        String username = TEST_USER_PREFIX + System.currentTimeMillis()
                + "_" + (int) (Math.random() * 1000);
        String registerJson = "{\"username\":\"" + username + "\",\"password\":\"" + TEST_PASSWORD + "\"}";

        ResponseEntity<byte[]> reg = httpPostJson("/api/auth/register", registerJson, null);
        assertEquals(200, reg.getStatusCode().value(),
                "注册接口应返回 HTTP 200，实际：" + reg.getStatusCode() + " " + bodyOf(reg));

        ResponseEntity<byte[]> login = httpPostJson("/api/auth/login", registerJson, null);
        assertEquals(200, login.getStatusCode().value(),
                "登录接口应返回 HTTP 200，实际：" + login.getStatusCode() + " " + bodyOf(login));
        JsonNode node = jsonOf(login);
        assertEquals(200, node.path("code").asInt(), "登录业务码应为 200：" + bodyOf(login));
        String token = node.path("data").path("token").asText();
        long userId = node.path("data").path("user").path("id").asLong();
        assertNotNull(token, "登录应返回 token");
        assertTrue(token.length() > 20, "token 长度异常：" + token);
        assertTrue(userId > 0, "登录应返回 userId，实际：" + userId);
        return new AuthSession(username, token, userId);
    }

    protected long createSession(String token) {
        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/session", "{}", token);
        assertEquals(200, resp.getStatusCode().value(), "创建会话失败：" + bodyOf(resp));
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(), "创建会话业务码应为 200：" + bodyOf(resp));
        return node.path("data").path("id").asLong();
    }

    /** 上传一个文本类文档（走真实 multipart），返回 Document JSON */
    protected JsonNode uploadDoc(String token, String filename, String content, String category) {
        ResponseEntity<byte[]> resp = httpUpload("/api/document/upload", filename,
                content.getBytes(StandardCharsets.UTF_8), category, token);
        assertEquals(200, resp.getStatusCode().value(), "上传失败：" + bodyOf(resp));
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(), "上传业务码应为 200：" + bodyOf(resp));
        return node.path("data");
    }

    protected void embedDoc(String token, long documentId) {
        ResponseEntity<byte[]> resp = httpPostJson("/api/document/embed/" + documentId, null, token);
        assertEquals(200, resp.getStatusCode().value(), "向量化失败：" + bodyOf(resp));
        assertEquals(200, jsonOf(resp).path("code").asInt(), "向量化业务码应为 200：" + bodyOf(resp));
    }

    /** 通过 HTTP 提问，返回 data 节点（ChatMessage：content / sources / role） */
    protected JsonNode ask(String token, long sessionId, String question) {
        String json = "{\"question\":\"" + question.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        ResponseEntity<byte[]> resp = httpPostJson("/api/chat/ask/" + sessionId, json, token);
        JsonNode node = jsonOf(resp);
        assertEquals(200, node.path("code").asInt(),
                "问答业务码应为 200，实际 HTTP " + resp.getStatusCode() + "：" + bodyOf(resp));
        return node.path("data");
    }

    protected String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ==================== Milvus 写入可见性等待 ====================

    /**
     * 等待"刚向量化入库的分块"真正可被检索到。
     *
     * 【为什么需要】Milvus 默认一致性级别为 Bounded（可容忍亚秒级陈旧），insert 返回成功
     * 不等于立即可被 search 命中：实测同一用例连续运行时，"插入成功 → 250ms 后检索" 有时返回 2 条、
     * 有时返回 0 条。直接把"插入后立刻检索"当断言，会得到与业务无关的随机失败（假失败）。
     * 生产链路上因为中间隔着一次查询改写的 LLM 调用（1~3s），用户几乎不会察觉该窗口；
     * 但验收用例必须确定性，故统一改为"轮询到期或命中为止"。
     *
     * @return true = 在超时内检索到含 mustContain 的分块；false = 超时仍未可见
     */
    protected static boolean waitUntilRetrievable(
            com.liushuwen.rag.document.service.MilvusService milvus,
            float[] queryVector, long documentId, String mustContain, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                List<com.liushuwen.rag.document.service.MilvusService.SearchResult> hits =
                        milvus.search(queryVector, 5, List.of(documentId));
                boolean hit = !hits.isEmpty() && hits.stream().anyMatch(h ->
                        h.getContent() != null && (mustContain == null || h.getContent().contains(mustContain)));
                if (hit) {
                    if (attempt > 1) {
                        System.out.println("[验收] 向量可见性等待 " + attempt + " 次后命中（Milvus Bounded 一致性窗口）");
                    }
                    return true;
                }
            } catch (Exception ignored) {
                // 单次检索异常不致命，继续重试直至超时
            }
            if (System.currentTimeMillis() >= deadline) {
                System.out.println("[验收] 向量可见性等待超时：" + timeoutMillis + "ms / " + attempt + " 次仍未命中");
                return false;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** 默认 15s 超时的可见性等待 */
    protected static boolean waitUntilRetrievable(
            com.liushuwen.rag.document.service.MilvusService milvus,
            float[] queryVector, long documentId, String mustContain) {
        return waitUntilRetrievable(milvus, queryVector, documentId, mustContain, 15000);
    }

    /** 供日志/报告使用的用例标记 */
    protected static void step(String msg) {
        System.out.println("[验收] " + msg);
    }
}
