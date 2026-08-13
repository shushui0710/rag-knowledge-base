package com.liushuwen.rag.document.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.config.RagProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import io.milvus.response.SearchResultsWrapper;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;


import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus向量数据库操作服务
 *
 * Milvus是什么？
 * 专门存"向量"的数据库。MySQL存的是文字数字，Milvus存的是向量（2048个浮点数）。
 * 它的核心能力是"向量相似度搜索"——给一个查询向量，找出最相似的K个向量。
 *
 * 本类提供三个核心操作：
 * 1. ensureCollection() - 建表（如果不存在的话）
 * 2. insertVectors() - 插入向量数据
 * 3. search() - 向量搜索（下周问答功能用）
 *
 * ⚠️ v1 / v2 双 API 说明（SDK 2.5.14，两个客户端 Bean 见 MilvusConfig）：
 * - v1（milvusServiceClient）：稠密路（search/insertVectors/deleteByDocumentId/ensureCollection）
 * - v2（milvusClientV2）：BM25 稀疏路（TODO 2-1 路线A：createHybridCollection/hybridSearch）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusServiceClient;

    /** v2 API 客户端（TODO 2-1 路线A：BM25 Function 建表 + EmbeddedText 稀疏检索） */
    private final MilvusClientV2 milvusClientV2;

    /** RAG 配置（alpha 融合权重等） */
    private final RagProperties ragProperties;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    /**
     * 启动时自动建表
     * @PostConstruct = "这个Bean创建后自动执行这个方法"
     */
    @PostConstruct
    public void init() {
        try {
            ensureCollection();
            ensureMemoryCollection();   // 阶段4：记忆专用 collection
        } catch (Exception e) {
            log.warn("Milvus初始化失败（可能Milvus还没启动）: {}", e.getMessage());
        }
    }

    /**
     * 创建Collection（相当于MySQL的CREATE TABLE）
     *
     * Milvus的Collection需要定义"字段"（Schema），就像MySQL建表要定义列。
     * 我们需要4个字段：
     * - id: 主键（对应MySQL document_chunk.id）
     * - document_id: 文档ID（用于按文档过滤）
     * - content: 文本内容（搜索时直接返回，不用再查MySQL）
     * - embedding: 向量字段（2048维浮点数组，核心字段）
     */
    public void ensureCollection() {
        try {
            // 先检查collection是否已存在
            R<ShowCollectionsResponse> showResp = milvusServiceClient.showCollections(
                    ShowCollectionsParam.newBuilder().build());
            for (String name : showResp.getData().getCollectionNamesList()) {
                if (name.equals(collectionName)) {
                    log.info("Milvus collection已存在: {}", collectionName);
                    return;
                }
            }

            // ============================================================
            // TODO 4（⭐⭐⭐ 难度）：定义Collection的Schema（字段列表）
            //
            // 需要定义4个字段，每个字段用 FieldType 描述：
            //
            // 字段1 - id（主键）：
            //   FieldType.newBuilder()
            //       .withName("id")              // 字段名
            //       .withDataType(DataType.Int64) // 数据类型：64位整数
            //       .withPrimaryKey(true)         // 是主键
            //       .withAutoID(false)            // 不自动生成ID（用MySQL的chunk id）
            //       .build()
            //
            // 字段2 - document_id（文档ID）：
            //   FieldType.newBuilder()
            //       .withName("document_id")
            //       .withDataType(DataType.Int64)
            //       .build()
            //
            // 字段3 - content（文本内容）：
            //   FieldType.newBuilder()
            //       .withName("content")
            //       .withDataType(DataType.VarChar) // 变长字符串
            //       .withMaxLength(2048)            // 最大长度
            //       .build()
            //
            // 字段4 - embedding（向量，核心字段）：
            //   FieldType.newBuilder()
            //       .withName("embedding")
            //       .withDataType(DataType.FloatVector) // 浮点向量
            //       .withDimension(dimension)           // 维度2048
            //       .build()
            //
            // 然后创建 CreateCollectionParam：
            //   CreateCollectionParam.newBuilder()
            //       .withCollectionName(collectionName)
            //       .withFieldTypes(List.of(idField, documentIdField, contentField, embeddingField))
            //       .build()
            //
            // 把下面这段替换成你的实现：
            // ============================================================
            FieldType idField = FieldType.newBuilder()
                    .withName("id")
                    .withDataType(DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(false)
                    .build();
            FieldType documentIdField=FieldType.newBuilder()
                    .withName("document_id")
                    .withDataType(DataType.Int64)
                    .build();
            FieldType contentField=FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(2048)
                    .build();
            FieldType embeddingField=FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build();

            List<FieldType> fieldTypes = List.of(idField, documentIdField, contentField, embeddingField);
            CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                    .withFieldTypes(fieldTypes) 
                    .build();
            
            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withSchema(schema)
                    .build();



            milvusServiceClient.createCollection(createParam);
            log.info("Milvus collection创建成功: {}", collectionName);

            // 创建向量索引（加速能搜索）
            CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)           // 集合名称
                    .withFieldName("embedding")                    // 向量字段名
                    .withIndexType(IndexType.IVF_FLAT)             // 索引类型
                    .withMetricType(MetricType.COSINE)             // 相似度度量
                    .withExtraParam("{\"nlist\":1024}")            // 索引参数（JSON 字符串）
                    .build();
            milvusServiceClient.createIndex(createIndexParam);

            // 加载到内存（搜索前必须先load）
            milvusServiceClient.loadCollection(
                    LoadCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            log.info("Milvus collection索引创建+加载完成: {}", collectionName);

        } catch (Exception e) {
            log.error("创建Milvus collection失败: {}", e.getMessage());
            throw new BusinessException("Milvus初始化失败: " + e.getMessage());
        }
    }

    /**
     * 批量插入向量
     *
     * @param chunkIds   文本块ID列表（作为Milvus的主键）
     * @param documentId 所属文档ID
     * @param contents   文本内容列表
     * @param vectors    向量列表（和contents一一对应）
     */
    public void insertVectors(List<Long> chunkIds, Long documentId,
                              List<String> contents, List<float[]> vectors) {
        try {
            // 构建插入数据（Milvus 2.5+ SDK 用 Gson 的 JsonObject 表示一行数据；
            // ⚠️ 2.4.x 曾是 FastJSON，SDK 升级后必须同步改，否则编译报"不兼容的类型"）
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

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withRows(rows)
                    .build();

            milvusServiceClient.insert(insertParam);
            log.info("Milvus插入成功: {}条向量, documentId={}", chunkIds.size(), documentId);

        } catch (Exception e) {
            log.error("Milvus插入失败: {}", e.getMessage());
            throw new BusinessException("向量入库失败: " + e.getMessage());
        }
    }

    /**
     * 向量搜索（下周问答功能会用到）
     *
     * @param queryVector 查询向量（2048维）
     * @param topK        返回最相似的K条结果
     * @return 搜索结果列表
     */
    public List<SearchResult> search(float[] queryVector, int topK) {
        try {
            // ============================================================
            // TODO 5（⭐⭐ 难度）：构建搜索参数
            //
            // 需要用 SearchParam.newBuilder() 构建，需要设置：
            //   .withCollectionName(collectionName)    // 搜哪个表
            //   .withVectorFieldName("embedding")     // 搜哪个字段
            //   .withVectors(List.of(queryVector))    // 查询向量
            //   .withVectorValues(queryVector)        // 或者用这个
            //   .withTopK(topK)                       // 返回前K条
            //   .withOutFields(List.of("id", "content", "document_id"))  // 返回哪些字段
            //   .withMetricType(MetricType.COSINE)    // 余弦相似度
            //   .withParams("{\"nprobe\":10}")        // 搜索参数
            //
            // 提示：查询向量要用 List.of(new float[][]{queryVector}) 或
            //       .withVectors(List.of(queryVector))

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(queryVector))
                    .withTopK(topK)
                    .withOutFields(List.of("id", "content", "document_id"))
                    .withMetricType(MetricType.COSINE)
                    .withParams("{\"nprobe\":10}")
                    .build();


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
     * 搜索结果内部类
     */
    @lombok.Data
    public static class SearchResult {
        private Long chunkId;
        private float score;
        private String content;
    }

    // ============================================================
    // 阶段4 ✅ 已实现：长期记忆（独立 qa_memory collection，与文档向量完全隔离）
    // ============================================================

    /** 记忆专用 collection（避免记忆混入文档检索结果） */
    private static final String MEMORY_COLLECTION = "qa_memory";

    /** 记忆主键自增（记忆行没有 document_id，id 直接自增） */
    private final java.util.concurrent.atomic.AtomicLong memoryIdSeq = new java.util.concurrent.atomic.AtomicLong(1);

    /**
     * 创建记忆 collection（启动时 init() 调用；幂等：已存在则跳过）
     * 字段：id(主键) / content(问题\n回答) / embedding(向量)
     */
    public void ensureMemoryCollection() {
        try {
            R<ShowCollectionsResponse> showResp = milvusServiceClient.showCollections(
                    ShowCollectionsParam.newBuilder().build());
            for (String name : showResp.getData().getCollectionNamesList()) {
                if (name.equals(MEMORY_COLLECTION)) {
                    return;
                }
            }
            FieldType idField = FieldType.newBuilder()
                    .withName("id").withDataType(DataType.Int64)
                    .withPrimaryKey(true).withAutoID(false).build();
            FieldType contentField = FieldType.newBuilder()
                    .withName("content").withDataType(DataType.VarChar)
                    .withMaxLength(2048).build();
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding").withDataType(DataType.FloatVector)
                    .withDimension(dimension).build();
            milvusServiceClient.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withSchema(CollectionSchemaParam.newBuilder()
                            .withFieldTypes(List.of(idField, contentField, embeddingField))
                            .build())
                    .build());
            milvusServiceClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\":1024}")
                    .build());
            milvusServiceClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION).build());
            log.info("Milvus 记忆 collection 创建成功: {}", MEMORY_COLLECTION);
        } catch (Exception e) {
            log.warn("记忆 collection 初始化失败: {}", e.getMessage());
        }
    }

    /**
     * 保存一条记忆（问答对，content 存 "问题\n回答"）
     * ⚠️ 记忆是旁路增强：失败只记日志，绝不影响问答主流程
     */
    public void insertMemory(float[] vector, String question, String answer) {
        try {
            JsonObject row = new JsonObject();
            row.addProperty("id", memoryIdSeq.incrementAndGet());
            row.addProperty("content", question + "\n" + answer);
            JsonArray arr = new JsonArray();
            for (float v : vector) {
                arr.add(v);
            }
            row.add("embedding", arr);
            milvusServiceClient.insert(InsertParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withRows(List.of(row))
                    .build());
            log.info("记忆已保存: id={}, question={}", row.get("id").getAsLong(), question);
        } catch (Exception e) {
            log.warn("记忆保存失败（不影响本次回答）: {}", e.getMessage());
        }
    }

    /**
     * 召回相关记忆（按向量相似度）
     * ⚠️ 失败返回空列表（等同"没有记忆"），不抛异常
     */
    public List<SearchResult> searchMemory(float[] vector, int topK) {
        try {
            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(vector))
                    .withTopK(topK)
                    .withOutFields(List.of("id", "content"))
                    .withMetricType(MetricType.COSINE)
                    .withParams("{\"nprobe\":10}")
                    .build();
            R<SearchResults> response = milvusServiceClient.search(param);
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                SearchResult sr = new SearchResult();
                sr.setChunkId(wrapper.getIDScore(0).get(i).getLongID());
                sr.setScore(wrapper.getIDScore(0).get(i).getScore());
                sr.setContent(wrapper.getFieldData("content", 0).get(i).toString());
                results.add(sr);
            }
            return results;
        } catch (Exception e) {
            log.warn("记忆召回失败（按无记忆处理）: {}", e.getMessage());
            return List.of();
        }
    }

    // ============================================================
    // 阶段1/2 新增：按文档删除向量 + 混合检索（骨架）
    // ============================================================

    /**
     * 按文档ID删除向量（阶段1-增量更新 TODO）
     *
     * @param documentId 文档ID
     */
    public void deleteByDocumentId(Long documentId) {
        // ============================================================
        // TODO 1-1b（⭐ 难度）：Milvus 按字段删除
        //
        // 【思路提示】
        //   DeleteParam 构造 → milvusServiceClient.delete(param) → 确认 deleteCount > 0
        //

        //
        // 面试考点：
        // - 布尔表达式语法 in [x]（Milvus 官方 delete 文档示例一致）
        // - 删除后确认 deleteCount>0，防止旧向量残留（搜到已删除文档）
        // - v1/v2 API 差异坑：v1 = DeleteParam → R<MutationResult>.getDeleteCnt()；
        //   v2 = DeleteReq → DeleteResp（io.milvus.v2...response.DeleteResp），别混用
        // - 兜底方案：若对非主键字段删除有兼容疑虑，可先用 MySQL 查该文档
        //   的 chunkIds，再按主键 "id in [chunkIds]" 删除
        // - 异常处理规范：与 search()/insertVectors() 一致——catch(Exception) → log.error
        //   → throw BusinessException（异常体系见 common/BusinessException + GlobalExceptionHandler）
        // ============================================================

        try {
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("document_id in [" + documentId + "]")   // 布尔表达式：in [x]
                    .build();
            // v1 API：delete(DeleteParam) 返回 R<MutationResult>，删除条数取 getDeleteCnt()
            R<MutationResult> resp = milvusServiceClient.delete(param);
            long deleted = resp.getData().getDeleteCnt();
            log.info("删除向量: documentId={}, deleteCount={}", documentId, deleted);
        } catch (Exception e) {
            log.error("删除向量失败: documentId={}, error={}", documentId, e.getMessage(), e);
            throw new BusinessException("删除向量失败: " + e.getMessage());
        }
    }

    /**
     * 混合检索（阶段2 TODO）：稠密向量 + BM25 稀疏向量双路召回 + 分数融合
     *
     * 骨架实现：退化为纯稠密检索（保证可运行）。
     *
     * @param queryVector 稠密查询向量
     * @param topK        返回条数
     * @return 检索结果
     */
    public List<SearchResult> hybridSearch(float[] queryVector, int topK) {
        // 兼容旧签名：只走稠密路（TODO 2-1 完整实现请看下方
        // hybridSearch(String queryText, float[] queryVector, int topK)）
        return search(queryVector, topK);
    }

    // ============================================================


    /**
     * TODO 2-1（路线A）建表：创建含 BM25 Function 的混合检索 collection（v2 API）
     *
     * 服务端自动行为：
     * - content 字段开启 analyzer（分词器）
     * - 插入数据时，服务端自动按 BM25 Function 把 content 转成 bm25_vector 稀疏向量
     *   （插入代码无需改！现有 v1 insertVectors 只要 row 里有 content 字段即可）
     *
     * 调用方式：项目启动后手动调用一次（或加个接口触发），例如在 DocumentController
     * 或测试中调用 milvusService.createHybridCollection()
     */
    public void createHybridCollection() {
        try {
            // 幂等：已存在则跳过（如需重建，先 drop 旧 collection）
            if (Boolean.TRUE.equals(milvusClientV2.hasCollection(
                    HasCollectionReq.builder().collectionName(collectionName).build()))) {
                log.warn("collection 已存在，跳过创建：{}（如需用新结构重建，请先 drop 旧 collection）",
                        collectionName);
                return;
            }

            // 1) 定义 Schema（字段 + BM25 Function）
            // ⚠️ 这里必须用 v2 的 DataType（io.milvus.v2.common.DataType）全限定名，
            //    因为本类 v1 代码（ensureCollection）用了 io.milvus.grpc.DataType，
            //    两个枚举同名冲突，统一用全限定名避免歧义
            CreateCollectionReq.CollectionSchema schema =
                    CreateCollectionReq.CollectionSchema.builder().build();
            schema.addField(AddFieldReq.builder()
                    .fieldName("id").dataType(io.milvus.v2.common.DataType.Int64)
                    .isPrimaryKey(true).autoID(false).build());   // ⚠️ 必须 false：现有 v1 insertVectors 显式传 chunkId 作为 id，autoID=true 会插入冲突
            schema.addField(AddFieldReq.builder()
                    .fieldName("content").dataType(io.milvus.v2.common.DataType.VarChar)
                    .maxLength(4096).enableAnalyzer(true).build());   // ⚠️ 文本字段必须开 analyzer
            schema.addField(AddFieldReq.builder()
                    .fieldName("embedding").dataType(io.milvus.v2.common.DataType.FloatVector)
                    .dimension(dimension).build());                  // 稠密向量（沿用现有）
            schema.addField(AddFieldReq.builder()
                    .fieldName("bm25_vector").dataType(io.milvus.v2.common.DataType.SparseFloatVector).build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("text_bm25_emb")
                    .inputFieldNames(List.of("content"))
                    .outputFieldNames(List.of("bm25_vector"))
                    .build());                                       // 服务端自动 BM25 分词

            // 2) 建表 + 稀疏向量索引 + 加载
            milvusClientV2.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName).collectionSchema(schema).build());
            milvusClientV2.createIndex(CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(List.of(IndexParam.builder()
                            .fieldName("bm25_vector")
                            .indexType(IndexParam.IndexType.AUTOINDEX)
                            .metricType(IndexParam.MetricType.BM25)
                            .build()))
                    .build());
            milvusClientV2.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName).build());
            log.info("混合检索 collection 创建成功（含 BM25 Function）: {}", collectionName);
        } catch (Exception e) {
            log.error("创建混合检索 collection 失败: {}", e.getMessage(), e);
            throw new BusinessException("Milvus collection 初始化失败: " + e.getMessage());
        }
    }

    /**
     * TODO 2-1（路线A）混合检索：稠密（v1）+ 稀疏（v2 BM25）双路召回 + 加权融合
     *
     * @param queryText   用户问题原文（稀疏路直接传文本，服务端自动 BM25 分词）
     * @param queryVector 用户问题稠密向量（稠密路用）
     * @param topK        返回条数
     * @return 融合排序后的检索结果（content 取自稠密路，v1 SearchResult）
     */
    public List<SearchResult> hybridSearch(String queryText, float[] queryVector, int topK) {
        try {
            // ---- 稠密路（v1 现有方法，复用）----
            List<SearchResult> dense = search(queryVector, topK);

            // ---- 稀疏路（v2：EmbeddedText 传文本，服务端自动 BM25 分词）----
            SearchResp sparseResp = milvusClientV2.search(SearchReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(new EmbeddedText(queryText)))
                    .annsField("bm25_vector")
                    .topK(topK)
                    .outputFields(List.of("id", "content"))
                    .build());
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
            return search(queryVector, topK);
        }
    }
}