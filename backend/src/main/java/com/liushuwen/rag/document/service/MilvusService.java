package com.liushuwen.rag.document.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.config.MilvusConfig;
import com.liushuwen.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.dml.*;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 文档向量读写与检索服务：批量入库、稠密检索、按文档删除、混合检索（稠密 + BM25 稀疏双路召回）。
 *
 * 职责边界（本类只做**文档向量的数据面**）：
 *   - 集合的建/删/探测/影子表切换 ⇒ {@link MilvusCollectionManager}
 *   - 长期记忆库（qa_memory）⇒ {@link MilvusMemoryStore}
 *   - 本类：文档向量本身（insertVectors / search / hybridSearch / deleteByDocumentId）
 *   三者同包协作，调用方按需注入，不再有"一个类做三件事"的巨型门面。
 *
 * 【设计要点】v1/v2 双 SDK 共存：v1(MilvusServiceClient) 稳定用于稠密路与删除，
 * v2(MilvusClientV2) 才支持 BM25 稀疏检索与 Function 生成字段插入，各取所长。
 * 【常见问题】向量库为什么单独存？——Milvus 专为 ANN 相似度检索优化，MySQL 不适合高维向量检索；
 *   embedding 维度是多少？——embedding-3 稠密向量 2048 维（取自 MilvusConfig，不再各处写死）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusServiceClient;

    /** v2 客户端：BM25 稀疏检索 + 带 Function 生成字段的插入（v1 不支持） */
    private final MilvusClientV2 milvusClientV2;

    /** RAG 配置：混合检索 alpha 加权融合权重等 */
    private final RagProperties ragProperties;

    /** Milvus 配置：主 collection 名（向量读写与检索都作用于主表） */
    private final MilvusConfig milvusConfig;

    /**
     * 批量插入向量（主 collection）
     *
     * @param chunkIds   文本块ID列表（作为Milvus的主键）
     * @param documentId 所属文档ID
     * @param contents   文本内容列表
     * @param vectors    向量列表（和contents一一对应）
     */
    public void insertVectors(List<Long> chunkIds, Long documentId,
                              List<String> contents, List<float[]> vectors) {
        insertVectors(milvusConfig.getCollectionName(), chunkIds, documentId, contents, vectors);
    }

    /**
     * 批量插入向量到指定 collection。
     * 【设计要点】索引重建的冷升级路径要把数据先灌进影子表，因此把目标集合名参数化；
     *   主流程仍走上面不带集合名的重载，调用方无需关心集合名
     *
     * @param targetName 目标 collection 名（主表或影子表）
     */
    public void insertVectors(String targetName, List<Long> chunkIds, Long documentId,
                              List<String> contents, List<float[]> vectors) {
        try {
            // 功能：构建插入数据，一行用 Gson JsonObject 表示｜要点：v1 SDK 行模型——2.5 起用 Gson，2.4.x 是 FastJSON，升级 SDK 须同步替换否则编译报"不兼容的类型"
            List<JsonObject> rows = new ArrayList<>();
            for (int i = 0; i < chunkIds.size(); i++) {
                JsonObject row = new JsonObject();
                row.addProperty("id", chunkIds.get(i));
                row.addProperty("document_id", documentId);
                row.addProperty("content", contents.get(i));

                // 向量字段需要转成 Gson JsonArray
                JsonArray vectorArray = new JsonArray();
                for (float v : vectors.get(i)) {
                    vectorArray.add(v);
                }
                row.add("embedding", vectorArray);

                rows.add(row);
            }

            // ⚠️ 必须走 v2 insert：混合 collection 带 BM25 Function（bm25_vector 由服务端生成），
            //    v1 insert 的 ParamUtils 校验器要求行数据提供全部字段，会报
            //    "The field: bm25_vector is not provided"；v2 insert 识别 Function 生成字段，跳过校验
            milvusClientV2.insert(InsertReq.builder()
                    .collectionName(targetName)
                    .data(rows)
                    .build());
            log.info("Milvus插入成功: {}条向量, collection={}, documentId={}", chunkIds.size(), targetName, documentId);

        } catch (Exception e) {
            log.error("Milvus插入失败: {}", e.getMessage());
            throw new BusinessException("向量入库失败: " + e.getMessage());
        }
    }

    /**
     * 纯稠密检索便捷入口：不按文档过滤，供评估等系统级调用使用。
     * 内部委托三参 search() 传 null 跳过 expr 过滤。
     * 【设计要点】方法重载分层：带过滤/不带过滤两个入口共用一套检索实现
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        // 功能：委托重载方法，documentIds 传 null 即不过滤
        return search(queryVector, topK, null);
    }

    /**
     * 稠密向量检索：支持按 documentIds 过滤，实现检索层的用户数据隔离。
     * 在问答链路中作为稠密召回主力，被 hybridSearch 稠密路复用。
     * 【设计要点】expr 布尔表达式过滤："document_id in [...]" 由服务端谓词下推过滤，
     * 避免全库检索后应用层再过滤的浪费
     * 【常见问题】documentIds 为空集合怎么办？——直接返回空列表，无需发起一次注定为空的 RPC；
     *   过滤为何放检索层而非查完再筛？——服务端过滤减少扫描量与传输量
     * @param queryVector 查询向量（2048维）
     * @param topK        返回最相似的K条结果
     * @param documentIds 只在该文档集合内检索（当前用户的文档ID列表）；null = 不过滤
     * @return 搜索结果列表
     */
    public List<SearchResult> search(float[] queryVector, int topK, List<Long> documentIds) {
        try {
            // ============================================================
            // 功能：构建 SearchParam（collection/向量字段/查询向量/topK/返回字段/COSINE/{"nprobe":10}）
            // 考点：v2.5 SDK 类型坑——FloatVector 查询向量必须传 List<Float>，
            //   传 float[] 会报 "Search target vector type is illegal"（2.4 时代可传 float[]）
            // ============================================================
            SearchParam.Builder paramBuilder = SearchParam.newBuilder()
                    .withCollectionName(milvusConfig.getCollectionName())
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(toVectorList(queryVector)))
                    .withTopK(topK)
                    .withOutFields(List.of("id", "content", "document_id"))
                    .withMetricType(MetricType.COSINE)
                    .withParams("{\"nprobe\":10}");
            // ⚠️ 检索层用户隔离：expr 按 document_id 过滤（旧 collection 已有该字段，无需重建即生效）
            if (documentIds != null) {
                if (documentIds.isEmpty()) {
                    return List.of();   // 用户没有任何文档 → 无需检索，直接走兜底
                }
                paramBuilder.withExpr("document_id in [" + documentIds.stream()
                        .map(String::valueOf).collect(Collectors.joining(",")) + "]");
            }
            SearchParam searchParam = paramBuilder.build();

            R<SearchResults> response = milvusServiceClient.search(searchParam);
            SearchResultsWrapper wrapper = new SearchResultsWrapper(
                    response.getData().getResults());

            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                SearchResult sr = new SearchResult();
                sr.setChunkId(wrapper.getIDScore(0).get(i).getLongID());
                sr.setScore(wrapper.getIDScore(0).get(i).getScore());
                sr.setContent(wrapper.getFieldData("content", 0).get(i).toString());
                results.add(sr);
            }

            log.info("Milvus搜索完成: topK={}, 返回{}条结果", topK, results.size());
            return results;

        } catch (Exception e) {
            log.error("Milvus搜索失败: {}", e.getMessage());
            throw new BusinessException("向量搜索失败: " + e.getMessage());
        }
    }

    /**
     * 向量命中结构（文档检索与长期记忆共用同一份定义，避免同一个 hit 类型出现两份）。
     * 【常见问题】跨类复用 MilvusMemoryStore 为什么也用这个类型？——记忆召回与文档召回
     *   在调用方眼里是同一种东西（chunkId + score + content），用两个同构类型只会增加转换代码。
     */
    @lombok.Data
    public static class SearchResult {
        private Long chunkId;
        private float score;
        private String content;
    }

    /**
     * float[] 转 List&lt;Float&gt; 的适配方法。
     * 【设计要点】SDK 版本兼容：Milvus 2.5 SDK 要求 FloatVector 查询向量必须是 List&lt;Float&gt;，
     * 传 float[] 会报 "Search target vector type is illegal"（2.4 时代可传 float[]）
     */
    private static List<Float> toVectorList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    /**
     * 按文档 ID 删除 Milvus 向量：文档删除/增量重解析时的向量级联清理入口。
     * 与 MySQL 删除配套，保证检索层不再召回已删除文档的内容。
     * 【设计要点】按非主键字段删除：expr 布尔表达式 "document_id in [x]"，语法与官方 delete 文档一致
     * 【常见问题】如何确认删除生效？——v1 返回 R&lt;MutationResult&gt;，getDeleteCnt() 应大于 0，防止旧向量残留；
     *   v2 则是 DeleteReq → DeleteResp（io.milvus.v2...response.DeleteResp），两套 API 勿混用；
     *   若担心非主键字段删除的兼容性，兜底可先查 MySQL 拿 chunkIds，再按主键 "id in [...]" 删除
     */
    public void deleteByDocumentId(Long documentId) {
        deleteByDocumentId(milvusConfig.getCollectionName(), documentId);
    }

    /**
     * 按文档 ID 删除指定 collection 中的向量。
     * 【设计要点】索引重建时目标集合可能是影子表，故把集合名参数化；主流程走上面的单参重载
     *
     * @param targetName 目标 collection 名（主表或影子表）
     */
    public void deleteByDocumentId(String targetName, Long documentId) {
        try {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(targetName)
                    .withExpr("document_id in [" + documentId + "]")   // 布尔表达式：in [x]
                    .build();
            // v1 API：delete(DeleteParam) 返回 R<MutationResult>，删除条数取 getDeleteCnt()
            R<MutationResult> resp = milvusServiceClient.delete(param);
            long deleted = resp.getData().getDeleteCnt();
            log.info("删除向量: collection={}, documentId={}, deleteCount={}", targetName, documentId, deleted);
        } catch (Exception e) {
            log.error("删除向量失败: documentId={}, error={}", documentId, e.getMessage(), e);
            throw new BusinessException("删除向量失败: " + e.getMessage());
        }
    }

    /**
     * 混合检索旧签名重载：只有查询向量、没有查询原文，无法走 BM25 稀疏路，退化为纯稠密检索。
     * 【设计要点】BM25 的输入是文本：稀疏向量由服务端对原文分词生成，仅有 queryVector 时稀疏路无从发起
     * 【常见问题】完整双路实现在哪？——见 hybridSearch(String queryText, float[] queryVector, int topK) 重载
     */
    public List<SearchResult> hybridSearch(float[] queryVector, int topK) {
        // 功能：无查询原文只能走稠密路｜要点：BM25 稀疏检索的输入是文本（EmbeddedText）而非向量
        return search(queryVector, topK);
    }

    /**
     * 混合检索核心实现：稠密（v1）+ 稀疏（v2 BM25）双路召回 + alpha 加权分数融合。
     * 问答链路的最终召回入口，兼顾语义相似（稠密）与关键词精确匹配（稀疏）。
     * 【设计要点】加权融合：score = alpha*稠密分 + (1-alpha)*稀疏分（alpha 默认 0.7，偏重语义路），
     * 按 chunkId 用 Map.merge 累加两路分数，降序取 TopK
     * 【常见问题】两路分数能直接相加吗？——COSINE 与 BM25 分数量纲不同，加权归一是工程近似，更严谨可用 RRF；
     *   content 为何只取稠密路？——两路 chunkId 一致，稠密路结果已带 content，免回查 MySQL
     * @param queryText   用户问题原文（稀疏路直接传文本，服务端自动 BM25 分词）
     * @param queryVector 用户问题稠密向量（稠密路用）
     * @param topK        返回条数
     * @param documentIds 只在当前用户文档内检索（检索层用户隔离）；null = 不过滤
     * @return 融合排序后的检索结果（content 取自稠密路，v1 SearchResult）
     */
    public List<SearchResult> hybridSearch(String queryText, float[] queryVector, int topK) {
        // 功能：委托四参重载，documentIds 传 null 即不过滤
        return hybridSearch(queryText, queryVector, topK, null);
    }

    public List<SearchResult> hybridSearch(String queryText, float[] queryVector, int topK, List<Long> documentIds) {
        try {
            // 用户没有任何文档 → 无需双路检索，直接返回空（走兜底文案）
            if (documentIds != null && documentIds.isEmpty()) {
                return List.of();
            }
            // ---- 稠密路（v1 现有方法，复用；documentIds 过滤实现检索层用户隔离）----
            List<SearchResult> dense = search(queryVector, topK, documentIds);

            // ---- 稀疏路（v2：EmbeddedText 传文本，服务端自动 BM25 分词；filter 同步按用户隔离）----
            var sparseBuilder = SearchReq.builder()
                    .collectionName(milvusConfig.getCollectionName())
                    .data(List.of(new EmbeddedText(queryText)))
                    .annsField("bm25_vector")
                    .topK(topK)
                    .outputFields(List.of("id", "content"));
            if (documentIds != null) {
                sparseBuilder.filter("document_id in [" + documentIds.stream()
                        .map(String::valueOf).collect(Collectors.joining(",")) + "]");
            }
            SearchResp sparseResp = milvusClientV2.search(sparseBuilder.build());
            List<SearchResp.SearchResult> sparseHits = sparseResp.getSearchResults().isEmpty()
                    ? List.of()
                    : sparseResp.getSearchResults().get(0);   // 判空防御

            // ---- 加权融合（alpha 从 rag.retrieval.hybrid-alpha 读，默认 0.7）----
            double alpha = ragProperties.getRetrieval().getHybridAlpha();
            Map<Long, Double> merged = new HashMap<>();
            dense.forEach(r -> merged.merge(r.getChunkId(),
                    alpha * r.getScore(), Double::sum));
            sparseHits.forEach(r -> merged.merge((Long) r.getId(),
                    (1 - alpha) * r.getScore().floatValue(), Double::sum));

            // ---- 按总分降序取 topK，content 从稠密路结果按 chunkId 找回 ----
            return merged.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(topK)
                    .map(e -> dense.stream()
                            .filter(r -> r.getChunkId().equals(e.getKey()))
                            .findFirst()
                            .orElseGet(() -> {
                                SearchResult r = new SearchResult();
                                r.setChunkId(e.getKey());
                                r.setScore(e.getValue().floatValue());
                                return r;
                            }))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // ⚠️ 降级而非抛异常：collection 未升级（无 bm25_vector / BM25 Function）时
            //    稀疏路会失败，此时退回纯稠密检索，保证问答主流程可用（与 Rerank 降级同理）
            log.warn("混合检索失败（稀疏路），降级为纯稠密检索: {}", e.getMessage());
            return degradeToDense(queryVector, topK, documentIds);
        }
    }

    /**
     * 混合检索降级实现：稀疏路不可用时退回纯稠密检索。
     * 【设计要点】降级不能丢安全不变式——修复前此处调用的是不带过滤的 search(queryVector, topK)
     * （documentIds 传 null），一旦稀疏路异常（旧 collection 未重建 BM25、或 Milvus 瞬时故障），
     * 检索会退化为"全库无过滤"，把其他用户的向量一并召回，构成跨租户内容泄露。
     * 降级只应降低召回质量，绝不放大可见范围，因此必须原样带上 documentIds。
     * 【常见问题】为什么独立成 public 方法？——验收用例要直接验证"降级仍隔离"这一安全不变式
     * （见 A3-08），而构造稀疏路故障需要真实触发异常，故把降级入口显式暴露出来便于验证。
     */
    public List<SearchResult> degradeToDense(float[] queryVector, int topK, List<Long> documentIds) {
        log.warn("混合检索降级为纯稠密检索（保留 documentIds 用户隔离：{}）",
                documentIds == null ? "null=不过滤" : documentIds.size() + " 个文档");
        return search(queryVector, topK, documentIds);
    }
}
