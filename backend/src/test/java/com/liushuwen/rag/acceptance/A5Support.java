package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import com.liushuwen.rag.llm.LlmCircuitBreaker;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5 系列（A5a~A5f）共用的夹具：把「跨多个测试类都要用」的三类东西集中在一处。
 *
 * 【为什么需要它 —— 拆分带来的新问题】
 *   A5 原先是一个 816 行的单文件，辅助代码（种子文档、熔断器强制开合、指标读取、报告正文判据）
 *   天然只写一份。按「变更原因」拆成 6 个类之后，这些辅助方法会被 2~3 个类同时需要，
 *   如果各自复制一份，就变成"改一处漏三处"——正是本轮结构治理要消灭的东西。
 *   因此抽成这个**非测试类**（不挂 @Test、无 Spring 上下文），只放无状态的静态夹具。
 *
 * 【为什么参数里有 AcceptanceSupport t】
 *   这些夹具要复用基类的受保护能力（uploadDoc / jsonOf / httpGet 等），
 *   为保持"无状态、可静态调用"，把调用方自己（this）作为参数传进来，而不是让夹具持有实例状态。
 *
 * 【为什么不把它们下沉进 AcceptanceSupport】
 *   基类是 A1~A7 共用的契约层（HTTP 客户端、响应解析、向量可见性等待），
 *   而这里的东西全带 Agent/熔断语义（种子文档的措辞、熔断器反射开合、报告正文判据）——
 *   塞进基类等于让所有套件都背着一份用不到的 Agent 词汇表，故单独放一层。
 */
final class A5Support {

    private A5Support() {
    }

    /**
     * 种子文档中"必被检索到"的那句话：既作为播种内容，也作为提问（保证检索命中），
     * 这样主问答链才会走到 llmService.chat 那一行——熔断保护的对象才被真正执行到。
     */
    static final String RETRIEVABLE_SENTENCE =
            "文档切片窗口为 512 个字符，相邻切片重叠 64 个字符，步长因此是 448。";

    // ---- 报告正文真实性判据（09-20 假产出防线）----

    /** 报告结构要素词：GenerateReportTool 的 Prompt 明确要求输出「引言/现状/问题/建议」四段 */
    private static final List<String> REPORT_SECTION_WORDS = List.of("引言", "现状", "问题", "建议");

    /** 可信报告正文的最小长度：与 AgentExecutor.DELIVERABLE_MIN_CHARS 同量级，低于此值必为概括或兜底文案 */
    private static final int REPORT_MIN_CHARS = 200;

    /**
     * 【假产出防线·09-20】断言一段回答"确实是报告正文"，而不是"关于报告的说明"。
     *
     * 背景：修复前 A5-13（乃至整套 64 条）只断言 answer 非空 + evidence 非空 + toolCalls 增长，
     * 而 LLM 会把工具产物概括成"报告已生成完成…"这类几百字客套话——三项断言照样全过，
     * 于是"报告正文一个字都没有"的假产出能一路全绿（实测三条问法产出 399/495/662 字，全是说明而非报告）。
     *
     * 判据：① 长度 ≥ {@value #REPORT_MIN_CHARS} 字；② 至少命中 2 个报告结构要素词。
     * 要求命中 ≥2 个：单词命中可能是偶然（如"建议您重试"里含"建议"），命中两个结构词基本只有真报告才做得到。
     */
    static void assertReportBody(String answer, String scene) {
        String a = answer == null ? "" : answer;
        assertTrue(a.length() >= REPORT_MIN_CHARS,
                scene + "：报告正文长度应 ≥ " + REPORT_MIN_CHARS + " 字，实际 " + a.length()
                        + " 字——过短说明 LLM 把产物概括成了「报告已生成」的说明而非正文。实际内容节选："
                        + a.substring(0, Math.min(160, a.length())));
        long hit = REPORT_SECTION_WORDS.stream().filter(a::contains).count();
        assertTrue(hit >= 2,
                scene + "：报告正文应至少命中 2 个结构要素词 " + REPORT_SECTION_WORDS
                        + "，实际命中 " + hit + " 个——命中不足说明回答不是结构化报告正文（假产出）。实际内容节选："
                        + a.substring(0, Math.min(240, a.length())));
    }

    // ---- 种子数据 ----

    /** 播种一篇"提问必命中"的文档并向量化（等 Milvus 可见性窗口），返回 docId */
    static long seedRetrievableDoc(AcceptanceSupport t, AcceptanceSupport.AuthSession s,
                                   MilvusService milvusService, EmbeddingService embeddingService) {
        String marker = "A5" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        StringBuilder sb = new StringBuilder();
        sb.append("# 系统参数说明\n\n");
        sb.append("文档唯一标记词：").append(marker).append("\n\n");
        sb.append("## 切片参数\n\n").append(RETRIEVABLE_SENTENCE).append("\n\n");
        sb.append("## 补充说明\n\n");
        for (int i = 0; i < 8; i++) {
            sb.append("补充说明 ").append(i).append("：本节用于增加文档体量，使检索需要真正区分多个片段。\n");
        }
        JsonNode doc = t.uploadDoc(s.token(), "系统参数说明.md", sb.toString(), "技术文档");
        long docId = doc.path("id").asLong();
        t.embedDoc(s.token(), docId);
        boolean visible = AcceptanceSupport.waitUntilRetrievable(milvusService,
                embeddingService.embedSingle(RETRIEVABLE_SENTENCE), docId, RETRIEVABLE_SENTENCE);
        assertTrue(visible, "前置条件：种子文档向量化后 15s 内仍不可检索（docId=" + docId + "）");
        return docId;
    }

    // ---- 指标读取 ----

    /** 读取今日指标 data 节点（供计数口径的增量断言使用） */
    static JsonNode metricsOf(AcceptanceSupport t, String token) {
        return t.jsonOf(t.httpGet("/api/metrics/today", token)).path("data");
    }

    // ---- 反射夹具 ----

    /** 反射判断某类是否持有指定类型的字段（用于断言"熔断器挂在哪一层"） */
    static boolean hasFieldOfType(Class<?> clazz, Class<?> fieldType) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType().equals(fieldType)) {
                return true;
            }
        }
        return false;
    }

    /** 读取指定类的私有字段值（用于断言"引擎直连端点与产品入口是否共用同一个引擎实例"） */
    static Object fieldValueOf(Class<?> clazz, String fieldName, Object target) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    /**
     * 把运行中的单例熔断器强制置为"打开"（openUntil = now + 60s），返回原值以便复位。
     * 【为什么用反射】熔断器没有提供"人为打开"的入口（生产代码不该为测试开后门），
     * 而验收要证明的恰恰是"真实链路用的那个熔断器一打开，全链路都走兜底"，只能直接改状态。
     */
    static long forceOpenBreaker(LlmCircuitBreaker breaker) throws Exception {
        Field f = LlmCircuitBreaker.class.getDeclaredField("openUntil");
        f.setAccessible(true);
        long backup = (long) f.get(breaker);
        f.set(breaker, System.currentTimeMillis() + 60_000L);
        return backup;
    }

    /** 复位熔断器（必须在 finally 中调用，否则会把同一个 JVM 里的后续用例全部熔断） */
    static void restoreBreaker(LlmCircuitBreaker breaker, long backup) throws Exception {
        Field f = LlmCircuitBreaker.class.getDeclaredField("openUntil");
        f.setAccessible(true);
        f.set(breaker, backup);
    }
}
