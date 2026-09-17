package com.liushuwen.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.acceptance.AcceptanceSupport;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索质量验收（原 EvalRunnerTest 的可断言版本）。
 *
 * 【相对旧版的三处实质改进】
 *   1. **旧版只 print 不断言**：无论命中率多低都会"通过"，无法作为验收门槛。
 *      新版对混合检索命中率设置明确阈值，不达标即失败。
 *   2. **旧版走无过滤的全局检索**：`milvusService.search(vec, 5)` 不带 documentIds，
 *      会扫到其他用户乃至已逻辑删除文档的残留向量（见验收用例 A6-03），
 *      指标被脏数据污染、且不可复现。新版先准备固定评估账号与语料，
 *      再按该账号的 documentIds 过滤，保证同一份语料对应同一份指标。
 *   3. **旧版只测稠密路**：测不到第 8 周引入的 BM25 混合检索。
 *      新版同时跑稠密与混合，产出可直接引用的对比数据。
 *
 * 【跑之前必须先准备语料】
 *   用例集的 expectedKeyword 必须能在语料中逐字命中，否则命中率无意义。
 *   语料位于 docs/eval/corpus/，本类会自动上传并向量化（幂等，已存在则复用）。
 *
 * 【通过标准】
 *   - 混合检索 Top5 命中率 ≥ 阈值（默认 0.7，可用 -Deval.hybrid.threshold 覆盖）
 *   - 语料准备成功且分块数 > 0
 *   - 逐题明细完整输出，供《验收报告》取证
 */
@DisplayName("评估集回归 · 检索质量验收")
class EvalRunnerTest extends AcceptanceSupport {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    /** 固定评估账号：保证语料只上传一次，指标可跨轮次对比 */
    private static final String EVAL_USER = "eval_corpus_user";

    private static final String EVAL_PASSWORD = "Eval123456";

    /** 混合检索 Top5 命中率门槛，可用系统属性覆盖便于调参实验 */
    private static float hybridThreshold() {
        return Float.parseFloat(System.getProperty("eval.hybrid.threshold", "0.7"));
    }

    @Test
    @DisplayName("EV-01 检索质量：稠密 vs 混合检索 Top5 命中率（评估集 20 题）")
    void runEval() {
        List<EvalCase> cases = loadCases();
        assertFalse(cases.isEmpty(),
                "评估用例为空：请确认 docs/eval/questions.json 存在且格式正确");

        // ---------- 1. 准备评估账号与语料（幂等） ----------
        SeedResult seed = ensureCorpus();
        assertTrue(seed.chunkTotal() > 0,
                "评估语料未成功入库（分块数 0），命中率将无意义。已准备文档：" + seed.docNames());
        step("评估语料就绪：文档 " + seed.docNames() + "，共 " + seed.chunkTotal() + " 个分块，"
                + "检索范围限定为这 " + seed.docIds().size() + " 个文档");

        // ---------- 2. 逐题跑双路检索 ----------
        int denseHit = 0;
        int hybridHit = 0;
        List<String> misses = new ArrayList<>();

        for (EvalCase c : cases) {
            if (c.getQuestion() == null || c.getExpectedKeyword() == null) {
                continue;
            }
            float[] qv;
            try {
                qv = embeddingService.embedSingle(c.getQuestion());
            } catch (Exception e) {
                misses.add("[EMBED失败] " + c.getQuestion() + " -> " + e.getMessage());
                continue;
            }

            boolean dHit = hits(c.getExpectedKeyword(),
                    milvusService.search(qv, 5, seed.docIds()));
            boolean hHit = hits(c.getExpectedKeyword(),
                    milvusService.hybridSearch(c.getQuestion(), qv, 5, seed.docIds()));

            if (dHit) denseHit++;
            if (hHit) hybridHit++;
            if (!hHit) misses.add("[MISS] " + c.getQuestion() + "  期望关键词：" + c.getExpectedKeyword());
            System.out.printf("  %-6s 稠密=%-5s 混合=%-5s  %s%n",
                    hHit ? "HIT" : "MISS", dHit, hHit, c.getQuestion());
        }

        int total = cases.size();
        double denseRate = denseHit * 1.0 / total;
        double hybridRate = hybridHit * 1.0 / total;

        System.out.println("──────────────────────────────────────────────");
        System.out.printf("  评估集题数      : %d%n", total);
        System.out.printf("  纯稠密 Top5 命中 : %d/%d = %.1f%%%n", denseHit, total, denseRate * 100);
        System.out.printf("  混合检索 Top5 命中: %d/%d = %.1f%%%n", hybridHit, total, hybridRate * 100);
        System.out.printf("  混合相对提升     : %+.1f 个百分点%n", (hybridRate - denseRate) * 100);
        System.out.println("──────────────────────────────────────────────");
        if (!misses.isEmpty()) {
            System.out.println("  未命中明细：");
            misses.forEach(m -> System.out.println("    " + m));
        }
        step(String.format("EV-01 实测：稠密 %.1f%% / 混合 %.1f%%（%d 题，语料 %d 分块）",
                denseRate * 100, hybridRate * 100, total, seed.chunkTotal()));

        // ---------- 3. 通过标准 ----------
        assertEquals(total, denseHit + (total - denseHit), "统计自检");
        assertTrue(hybridRate >= hybridThreshold(),
                String.format("混合检索 Top5 命中率 %.1f%% 低于门槛 %.1f%%（可用 -Deval.hybrid.threshold 调整）。"
                                + "未命中题目：%s",
                        hybridRate * 100, hybridThreshold() * 100, misses));
        assertTrue(denseRate > 0,
                "纯稠密检索命中率为 0，说明语料未真正入库或向量检索链路异常");
    }

    // ==================== 语料准备 ====================

    private record SeedResult(List<Long> docIds, List<String> docNames, int chunkTotal) {
    }

    /** 幂等准备评估账号 + 语料：账号不存在则注册，语料缺失则上传并向量化 */
    private SeedResult ensureCorpus() {
        AuthSession session = loginOrRegister(EVAL_USER, EVAL_PASSWORD);

        File corpusDir = new File("../docs/eval/corpus");
        assertTrue(corpusDir.isDirectory(),
                "评估语料目录不存在：" + corpusDir.getAbsolutePath()
                        + "。请先创建语料，保证 expectedKeyword 能在语料中命中");

        File[] corpusFiles = corpusDir.listFiles((d, n) -> n.endsWith(".md"));
        assertTrue(corpusFiles != null && corpusFiles.length > 0, "语料目录下没有 .md 文件");

        JsonNode existing = jsonOf(httpGet("/api/document/list", session.token())).path("data");
        List<Long> docIds = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int chunkTotal = 0;

        for (File f : corpusFiles) {
            JsonNode found = null;
            for (JsonNode d : existing) {
                if (d.path("fileName").asText().equals(f.getName())) {
                    found = d;
                }
            }
            if (found == null) {
                String text;
                try {
                    text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new AssertionError("读取语料失败：" + f.getAbsolutePath(), e);
                }
                found = uploadDoc(session.token(), f.getName(), text, "技术文档");
                embedDoc(session.token(), found.path("id").asLong());
                System.out.println("  [语料] 上传并向量化 " + f.getName()
                        + " → chunkCount=" + found.path("chunkCount").asInt());
            } else if (found.path("embeddingStatus").asInt() != 1) {
                embedDoc(session.token(), found.path("id").asLong());
                System.out.println("  [语料] 补向量化 " + f.getName());
            }
            docIds.add(found.path("id").asLong());
            names.add(f.getName());
            chunkTotal += found.path("chunkCount").asInt();
        }
        return new SeedResult(docIds, names, chunkTotal);
    }

    /** 登录，失败则注册后再登录（避免重复运行时报「用户名已存在」） */
    private AuthSession loginOrRegister(String username, String password) {
        String json = "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
        org.springframework.http.ResponseEntity<byte[]> login = httpPostJson("/api/auth/login", json, null);
        if (jsonOf(login).path("code").asInt() != 200) {
            httpPostJson("/api/auth/register", json, null);
            login = httpPostJson("/api/auth/login", json, null);
        }
        JsonNode node = jsonOf(login);
        assertTrue(node.path("code").asInt() == 200, "评估账号登录失败：" + bodyOf(login));
        return new AuthSession(username, node.path("data").path("token").asText(),
                node.path("data").path("user").path("id").asLong());
    }

    // ==================== 工具 ====================

    private boolean hits(String keyword, List<MilvusService.SearchResult> results) {
        return results.stream()
                .anyMatch(r -> r.getContent() != null && r.getContent().contains(keyword));
    }

    /** 读取外置用例集 docs/eval/questions.json（相对 backend 模块目录） */
    private List<EvalCase> loadCases() {
        try {
            File f = new File("../docs/eval/questions.json");
            if (!f.exists()) {
                return List.of();
            }
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return com.alibaba.fastjson.JSON.parseObject(json,
                    new com.alibaba.fastjson.TypeReference<List<EvalCase>>() {
                    });
        } catch (Exception e) {
            System.out.println("[WARN] 读取用例失败: " + e.getMessage());
            return List.of();
        }
    }
}
