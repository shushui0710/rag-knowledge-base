package com.liushuwen.rag.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.liushuwen.rag.document.service.EmbeddingService;
import com.liushuwen.rag.document.service.MilvusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 —— 检索链路验收（稠密检索 / 混合检索 / 用户隔离 / 中文稳定性）。
 *
 * 覆盖范围：
 *   1. 稠密检索（v1 API）：TopK 生效、分数降序、content 可用
 *   2. 混合检索（v1 稠密 + v2 BM25 稀疏 + alpha=0.7 加权融合）：稀疏路真的工作、词面命中生效
 *   3. 检索层用户隔离：documentIds 表达式过滤，A 检索不到 B 的向量
 *   4. 空文档集短路：无文档用户直接返回空（走兜底文案，不误调 LLM）
 *   5. 结果完整性：返回给上层的每条结果都必须带 content
 *   6. 【稳定性回归】连续中文检索不得打崩 Milvus
 *   7. 降级路径的安全不变式：降级为纯稠密后仍保留 documentIds 过滤
 *
 * 三条与已修缺陷直接相关的断言：
 *   - 缺陷②（createHybridCollection 漏建 embedding 索引 → loadCollection 报
 *     "no vector index on field"）：本类所有检索用例都依赖 collection 处于 loaded 状态，
 *     索引缺失时这些用例会集体失败，因此它们天然构成该缺陷的回归网。
 *   - 缺陷③（v1 insert 与 BM25 Function 冲突 → 向量根本写不进混合 collection）：
 *     通过"写入后必须能检索召回"间接覆盖（写入失败则召回为空）。
 *   - GBK 编码崩溃（中文 → 乱码字节流 → tantivy panic → Milvus SIGABRT）：
 *     由 A3-07 连续中文混合检索 + 容器健康探测专门覆盖。
 *   - 降级丢失用户过滤（hybridSearch catch 分支调用不带过滤的 search）：
 *     由 A3-08 直接覆盖（含"对照组"证明断言具备区分度）。
 *
 * 通过标准（P0）：
 *   - 稠密与混合检索均返回非空结果且分数降序、content 非空
 *   - 按他人 documentIds 过滤时检索不到对方内容；降级为纯稠密后同样检索不到
 *   - documentIds 为空列表时返回空列表
 *   - 连续 20 次中文混合检索无异常，且 Milvus /healthz 仍为健康
 */
@DisplayName("A3 检索链路验收")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class A3_RetrievalAcceptanceTest extends AcceptanceSupport {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private MilvusService milvusService;

    private static final String MILVUS_HEALTH = "http://localhost:9091/healthz";

    /** 上传 + 向量化一份带唯一标记词的文档，返回 docId 与标记词 */
    private record SeededDoc(long docId, String marker) {
    }

    private SeededDoc seedDocument(AuthSession s, String marker) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 检索验收文档\n\n");
        sb.append("本文档的唯一标记词是 ").append(marker).append("。\n");
        sb.append("文档切片窗口 512 字符，重叠 64 字符；向量维度 2048；混合检索 alpha 权重 0.7。\n");
        sb.append("检索召回 20 条后经重排序精排保留 5 条，低于 0.35 相似度阈值的片段会被过滤。\n");
        for (int i = 0; i < 12; i++) {
            sb.append("补充段落 ").append(i)
                    .append("：本段用于扩大文档体量，使分块数量足以支撑召回率验证。\n");
        }
        JsonNode doc = uploadDoc(s.token(), "检索验收文档.md", sb.toString(), "技术文档");
        long docId = doc.path("id").asLong();
        embedDoc(s.token(), docId);
        // 等待写入对检索可见：Milvus 默认 Bounded 一致性，insert 成功到可被 search 命中之间有亚秒级窗口，
        // 不等就会得到"插入成功但检索 0 条"的随机失败（与被测逻辑无关）
        boolean visible = waitUntilRetrievable(milvusService,
                embeddingService.embedSingle("本文档的唯一标记词是 " + marker), docId, marker);
        assertTrue(visible, "前置条件：文档向量化后 15s 内仍不可检索（docId=" + docId + "）");
        return new SeededDoc(docId, marker);
    }

    // ==================== 1. 稠密检索 ====================

    @Test
    @Order(1)
    @DisplayName("A3-01 稠密检索：TopK 生效、分数降序、content 非空")
    void a301_dense_search_contract() {
        AuthSession s = newUser();
        String marker = "DENSE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SeededDoc seed = seedDocument(s, marker);

        float[] qv = embeddingService.embedSingle("文档切片窗口和重叠是多少");
        List<MilvusService.SearchResult> hits = milvusService.search(qv, 5, List.of(seed.docId()));

        assertFalse(hits.isEmpty(), "稠密检索不应为空");
        assertTrue(hits.size() <= 5, "TopK=5 应生效，实际返回 " + hits.size());
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).getScore() >= hits.get(i).getScore(),
                    "结果应按分数降序，第 " + i + " 项逆序");
        }
        for (MilvusService.SearchResult h : hits) {
            assertNotNull(h.getChunkId(), "chunkId 不应为空");
            assertNotNull(h.getContent(), "content 不应为空（两库主键对齐后直接取向量库 payload）");
        }
        step("A3-01 通过：稠密检索 Top" + hits.size() + "，首条分数 "
                + String.format("%.4f", hits.get(0).getScore()));
    }

    // ==================== 2. 混合检索 ====================

    @Test
    @Order(2)
    @DisplayName("A3-02 混合检索：BM25 稀疏路生效，罕见词面可精确命中")
    void a302_hybrid_search_lexical_hit() {
        AuthSession s = newUser();
        // 构造一个中文语料里绝不会出现的罕见标识串，稠密向量几乎无法语义泛化，只能靠 BM25 词面命中
        String marker = "ZQ" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        SeededDoc seed = seedDocument(s, marker);

        float[] qv = embeddingService.embedSingle(marker);
        List<MilvusService.SearchResult> hits =
                milvusService.hybridSearch(marker, qv, 5, List.of(seed.docId()));

        assertFalse(hits.isEmpty(), "混合检索不应为空");
        boolean lexicalHit = hits.stream()
                .anyMatch(h -> h.getContent() != null && h.getContent().contains(marker));
        assertTrue(lexicalHit, "罕见标记词 " + marker + " 应被 BM25 稀疏路命中；若失败说明稀疏路降级为纯稠密"
                + "（collection 未按 BM25 结构建立）。实际召回 content："
                + hits.stream().map(h -> h.getContent() == null ? "null"
                        : h.getContent().substring(0, Math.min(50, h.getContent().length()))).toList());
        step("A3-02 通过：混合检索词面命中 " + marker + "（BM25 稀疏路工作正常，未降级）");
    }

    @Test
    @Order(3)
    @DisplayName("A3-03 混合检索与纯稠密检索的召回差异可观测（双路融合确实在起作用）")
    void a303_hybrid_differs_from_dense() {
        AuthSession s = newUser();
        String marker = "FUSE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SeededDoc seed = seedDocument(s, marker);
        String query = "混合检索的 alpha 权重和召回条数配置";
        float[] qv = embeddingService.embedSingle(query);

        List<MilvusService.SearchResult> dense = milvusService.search(qv, 5, List.of(seed.docId()));
        List<MilvusService.SearchResult> hybrid = milvusService.hybridSearch(query, qv, 5, List.of(seed.docId()));

        assertFalse(dense.isEmpty(), "稠密路应有结果");
        assertFalse(hybrid.isEmpty(), "混合路应有结果");
        // 融合后分数是 alpha*稠密 + (1-alpha)*稀疏 的加权和，与纯稠密分不应完全逐条相等
        String denseScores = dense.stream().map(r -> String.format("%.6f", r.getScore())).toList().toString();
        String hybridScores = hybrid.stream().map(r -> String.format("%.6f", r.getScore())).toList().toString();
        assertFalse(denseScores.equals(hybridScores),
                "混合检索分数应与纯稠密不同（否则说明稀疏路未参与融合）。dense=" + denseScores
                        + " hybrid=" + hybridScores);
        // 【实测备注】MilvusService.hybridSearch 的排序依据是"融合分"（0.7*稠密 + 0.3*稠密BM25），
        // 但对外暴露的 SearchResult.score 对"稠密路命中的分块"仍是稠密原分（仅稀疏路独有分块才回填融合分）。
        // 因此这里的差异通常表现为"同一批分数顺序不同"，而不是分数量纲变化 —— 这是记录在案的口径，
        // 使下游 minScore 阈值始终筛的是 COSINE 语义分（量纲一致），代价是 sources 展示分 ≠ 排序分。
        step("A3-03 通过：融合排序生效。dense=" + denseScores + " hybrid=" + hybridScores
                + "（注意：对外 score 仍取稠密原分，排序依据才是融合分）");
    }

    // ==================== 3. 用户隔离与短路 ====================

    @Test
    @Order(4)
    @DisplayName("A3-04 检索层用户隔离：按他人 documentIds 过滤检索不到对方内容")
    void a304_retrieval_isolation_by_document_ids() {
        AuthSession userA = newUser();
        AuthSession userB = newUser();
        String markerA = "ISOA" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SeededDoc seedA = seedDocument(userA, markerA);
        long docIdB = uploadDoc(userB.token(), "B的文档.md", "# B 的私有内容，含标记词 BONLY", "其他")
                .path("id").asLong();
        embedDoc(userB.token(), docIdB);

        float[] qv = embeddingService.embedSingle("本文档的唯一标记词是 " + markerA);

        // 用 A 的 documentIds 过滤：应命中 A 的内容
        List<MilvusService.SearchResult> asA = milvusService.search(qv, 5, List.of(seedA.docId()));
        assertTrue(asA.stream().anyMatch(h -> h.getContent() != null && h.getContent().contains(markerA)),
                "A 应能检索到自己的文档");

        // 用 B 的 documentIds 过滤：同一查询不得返回 A 的任何内容
        List<MilvusService.SearchResult> asB = milvusService.search(qv, 5, List.of(docIdB));
        assertTrue(asB.stream().noneMatch(h -> h.getContent() != null && h.getContent().contains(markerA)),
                "以 B 的 documentIds 检索时不得返回 A 的私有内容（向量层越权）");
        step("A3-04 通过：documentIds 表达式过滤生效，跨用户内容不可见");
    }

    @Test
    @Order(5)
    @DisplayName("A3-05 空文档集短路：documentIds 为空列表时直接返回空，不发起检索")
    void a305_empty_document_ids_short_circuits() {
        float[] qv = embeddingService.embedSingle("任意问题");
        List<MilvusService.SearchResult> hits = milvusService.hybridSearch("任意问题", qv, 5, List.of());

        assertTrue(hits.isEmpty(), "用户没有任何已向量化文档时应直接返回空列表（走兜底文案）");
        step("A3-05 通过：空 documentIds 短路返回空");
    }

    @Test
    @Order(6)
    @DisplayName("A3-06 结果完整性：混合检索返回的每条结果都必须带 content（否则下游生成 sources 会 NPE）")
    void a306_hybrid_results_always_carry_content() {
        AuthSession s = newUser();
        String marker = "CONT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SeededDoc seed = seedDocument(s, marker);
        String query = "本文档的唯一标记词是 " + marker;
        float[] qv = embeddingService.embedSingle(query);

        List<MilvusService.SearchResult> hits = milvusService.hybridSearch(query, qv, 20, List.of(seed.docId()));

        assertFalse(hits.isEmpty(), "混合检索不应为空");
        List<Long> noContent = new ArrayList<>();
        for (MilvusService.SearchResult h : hits) {
            if (h.getContent() == null) {
                noContent.add(h.getChunkId());
            }
        }
        assertTrue(noContent.isEmpty(),
                "混合检索只从稠密路取 content，仅被稀疏路命中的分块 content 为 null，"
                        + "会被 ChatServiceImpl 生成 sources 时解引用导致 500。本次出现 null content 的 chunkId="
                        + noContent
                        + "；共 " + hits.size() + " 条结果");
        step("A3-06 通过：混合检索 " + hits.size() + " 条结果 content 全部非空");
    }

    // ==================== 4. 中文稳定性回归（GBK → tantivy panic → SIGABRT） ====================

    @Test
    @Order(7)
    @DisplayName("A3-07 【稳定性回归】连续 20 次中文混合检索不得打崩 Milvus（tantivy 分词 panic）")
    void a307_chinese_search_does_not_crash_milvus() {
        assertTrue(milvusHealthy(), "验收开始时 Milvus 应处于健康状态，实际非健康");

        AuthSession s = newUser();
        String marker = "GBKCHK" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SeededDoc seed = seedDocument(s, marker);

        String[] questions = {
                "文档的分块窗口和重叠参数是多少",
                "向量维度是多少维",
                "混合检索的 alpha 权重如何配置",
                "重排序之后保留几条片段",
                "相似度阈值的默认值是多少",
        };
        int rounds = 20;
        for (int i = 0; i < rounds; i++) {
            String q = questions[i % questions.length];
            float[] qv = embeddingService.embedSingle(q);
            List<MilvusService.SearchResult> hits = milvusService.hybridSearch(q, qv, 5, List.of(seed.docId()));
            assertNotNull(hits, "第 " + (i + 1) + " 轮混合检索返回 null");
            assertFalse(hits.isEmpty(), "第 " + (i + 1) + " 轮中文混合检索结果为空（可能已降级为纯稠密或 Milvus 异常）");
        }

        assertTrue(milvusHealthy(),
                "连续 " + rounds + " 次中文混合检索后 Milvus 不再健康 —— "
                        + "典型的 GBK 编码导致 tantivy 分词器按多字节切分触发 Rust panic → 容器 SIGABRT。"
                        + "请确认测试 JVM 已带 -Dfile.encoding=UTF-8");
        step("A3-07 通过：连续 " + rounds + " 次中文混合检索完成，Milvus /healthz 仍然健康");
    }

    // ==================== 5. 降级路径的安全不变式 ====================

    @Test
    @Order(8)
    @DisplayName("A3-08 降级不丢隔离：混合检索降级为纯稠密时仍按 documentIds 过滤（不跨租户泄露）")
    void a308_degraded_dense_keeps_isolation() {
        AuthSession userA = newUser();
        AuthSession userB = newUser();
        String markerA = "DEGA" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String markerB = "DEGB" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        SeededDoc seedA = seedDocument(userA, markerA);
        SeededDoc seedB = seedDocument(userB, markerB);

        // 查询向量取 B 的标记词：若过滤丢失，这条查询会命中 B 的私有内容
        float[] qv = embeddingService.embedSingle("本文档的唯一标记词是 " + markerB);

        // 对照组：以 B 自己的 documentIds 检索应命中 B —— 先证明这条查询"确实能找到 B"，
        // 否则下面的"查不到 B"可能只是因为查询本身命不中，断言会变成空转。
        // 【为什么不用无过滤检索做对照】无过滤检索取的是全库 Top-5，而验收套件每轮都会往库里累积
        // 大量同模板语料（模板文字几乎一致、只有标记词不同），当轮文档是否进 Top-5 取决于累积量，
        // 会变成与被测逻辑无关的随机失败。按 B 自己的文档过滤则只在其 2~3 个分块内比较，结果确定。
        List<MilvusService.SearchResult> asB = milvusService.search(qv, 5, List.of(seedB.docId()));
        assertTrue(asB.stream().anyMatch(h -> h.getContent() != null && h.getContent().contains(markerB)),
                "对照组失败：B 自己的文档都检索不到自己的标记词，本用例失去区分度");

        // 被验证的降级路径：以 A 的 documentIds 过滤调用降级实现
        // （修复前 hybridSearch 的 catch 分支调用的是不带过滤的 search，降级即全库可见）
        List<MilvusService.SearchResult> degraded =
                milvusService.degradeToDense(qv, 5, List.of(seedA.docId()));

        assertTrue(degraded.stream().noneMatch(h -> h.getContent() != null && h.getContent().contains(markerB)),
                "降级为纯稠密检索后仍不得返回其他用户的内容（降级只应降低召回质量，不应放大可见范围）");
        step("A3-08 通过：降级路径保留 documentIds 过滤（对照组确认 B 可被检出，"
                + "以 A 的文档过滤时返回 " + degraded.size() + " 条且不含 B 的内容）");
    }

    /** 探测 Milvus 健康端点（docker-compose 里 healthcheck 用的就是它） */
    private boolean milvusHealthy() {        try {
            ResponseEntity<String> resp = new org.springframework.web.client.RestTemplate()
                    .getForEntity(MILVUS_HEALTH, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    static {
        // 保证 Milvus 健康探测不受 Clash 等本机代理影响
        System.setProperty("java.net.useSystemProxies", "false");
    }
}
