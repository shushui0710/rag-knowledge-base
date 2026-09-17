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
import io.milvus.v2.service.vector.request.InsertReq;
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
 * Milvus 向量库操作服务：封装建集合、批量入库、稠密检索、按文档删除，以及混合检索（稠密+BM25 稀疏）双路召回。
 * 在知识库链路中作为向量存储与检索层，下游被 DocumentService（入库/删除）与问答检索（hybridSearch）调用。
 * 【设计要点】v1/v2 双 SDK 共存：v1(MilvusServiceClient) 稳定用于稠密路，v2(MilvusClientV2) 才支持 BM25 Function 查询，各取所长
 * 【常见问题】向量库为什么单独存？——Milvus 专为 ANN 相似度检索优化，MySQL 不适合高维向量检索；
 *   embedding 维度是多少？——embedding-3 稠密向量 2048 维
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusService {

    private final MilvusServiceClient milvusServiceClient;

    /** v2 客户端：BM25 Function 建表 + EmbeddedText 稀疏检索（v1 不支持） */
    private final MilvusClientV2 milvusClientV2;

    /** RAG 配置：混合检索 alpha 加权融合权重等 */
    private final RagProperties ragProperties;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    /**
     * 应用启动时自动初始化 Milvus collection：主检索库与记忆库不存在则创建。
     * 在建库链路最前端执行，保证服务就绪即可入库检索，无需手工建表。
     * 【设计要点】@PostConstruct 生命周期：Bean 依赖注入完成后回调，适合启动期资源准备
     * 【常见问题】Milvus 未启动导致初始化失败怎么办？——catch 后仅 log.warn 不阻断应用启动（可用性优先），
     *   待 Milvus 恢复后重启即可补建；为何启动期不重试？——阻塞式重试会拖慢甚至卡死应用上线
     */
    @PostConstruct
    public void init() {
        try {
            ensureCollection();
            ensureMemoryCollection();   // 记忆专用 collection：与文档向量物理隔离，避免记忆混入检索结果
        } catch (Exception e) {
            log.warn("Milvus初始化失败（可能Milvus还没启动）: {}", e.getMessage());
        }
    }

    /**
     * 创建主检索 collection：定义 4 字段 Schema（id/document_id/content/embedding）+ IVF_FLAT 索引并加载。
     * 在建库链路中作为 v1 稠密路的结构基础，启动时由 init() 幂等调用。
     * 【设计要点】Schema 设计：id 对齐 MySQL document_chunk.id（autoID=false）便于回查关联，
     * content 直接存原文使检索免回 MySQL，embedding 为 2048 维 FloatVector
     * 【常见问题】为什么主键用 MySQL 的 chunkId？——两库主键对齐，融合排序后可直接定位分块；
     *   为什么建索引选 IVF_FLAT？——nlist=1024 聚类倒排 + COSINE 度量，中小规模数据集性价比高
     */
    public void ensureCollection() {
        try {
            // 功能：幂等检查——已存在直接跳过｜要点：重启安全（重复建表会报错，先 showCollections 判断）
            R<ShowCollectionsResponse> showResp = milvusServiceClient.showCollections(
                    ShowCollectionsParam.newBuilder().build());
            for (String name : showResp.getData().getCollectionNamesList()) {
                if (name.equals(collectionName)) {
                    log.info("Milvus collection已存在: {}", collectionName);
                    return;
                }
            }

            // ============================================================
            // 功能：定义 Collection Schema（4 个字段，FieldType 逐个声明名称/类型/主键/维度）
            // 考点：Schema 设计对齐 MySQL document_chunk 表
            // 常见问题：id 为什么 autoID(false)？→ 显式传 MySQL 的 chunkId 作主键，两库主键对齐可回查关联
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

            // 功能：为 embedding 建向量索引（IVF_FLAT + COSINE，nlist=1024）
            // 考点：ANN 索引选型——IVF_FLAT 聚类倒排加速近似检索，暴力遍历扛不住生产规模
            CreateIndexParam createIndexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)           // 集合名称
                    .withFieldName("embedding")                    // 向量字段名
                    .withIndexType(IndexType.IVF_FLAT)             // 索引类型
                    .withMetricType(MetricType.COSINE)             // 相似度度量
                    .withExtraParam("{\"nlist\":1024}")            // 索引参数（JSON 字符串）
                    .build();
            milvusServiceClient.createIndex(createIndexParam);

            // 功能：load collection 到内存｜要点：Milvus 检索前必须 load，数据从对象存储载入查询节点后才可查
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

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withRows(rows)
                    .build();

            // ⚠️ 必须走 v2 insert：混合 collection 带 BM25 Function（bm25_vector 由服务端生成），
            //    v1 insert 的 ParamUtils 校验器要求行数据提供全部字段，会报
            //    "The field: bm25_vector is not provided"；v2 insert 识别 Function 生成字段，跳过校验
            milvusClientV2.insert(InsertReq.builder()
                    .collectionName(collectionName)
                    .data(rows)
                    .build());
            log.info("Milvus插入成功: {}条向量, documentId={}", chunkIds.size(), documentId);

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
                    .withCollectionName(collectionName)
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
     * 搜索结果内部类
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
    private List<Float> toVectorList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) {
            list.add(v);
        }
        return list;
    }

    // ============================================================
    // 长期记忆：独立 qa_memory collection，与文档向量完全隔离
    // ============================================================

    /** 记忆专用 collection（避免记忆混入文档检索结果） */
    private static final String MEMORY_COLLECTION = "qa_memory";

    /** 记忆主键自增（记忆行没有 document_id，id 直接自增） */
    private final java.util.concurrent.atomic.AtomicLong memoryIdSeq = new java.util.concurrent.atomic.AtomicLong(1);

    /**
     * 创建记忆 collection（启动时 init() 调用；幂等：已存在则跳过）
     * 字段：id(主键) / user_id(所属用户，记忆也按用户隔离) / content(问题\n回答) / embedding(向量)
     * ⚠️ 旧版 qa_memory 无 user_id 字段：旧库上插入/召回会失败并静默降级（记忆自动停用），
     *    删除旧 collection 后重启应用即自动重建新结构
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
            FieldType userIdField = FieldType.newBuilder()
                    .withName("user_id").withDataType(DataType.Int64)
                    .build();
            FieldType contentField = FieldType.newBuilder()
                    .withName("content").withDataType(DataType.VarChar)
                    .withMaxLength(2048).build();
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding").withDataType(DataType.FloatVector)
                    .withDimension(dimension).build();
            milvusServiceClient.createCollection(CreateCollectionParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withSchema(CollectionSchemaParam.newBuilder()
                            .withFieldTypes(List.of(idField, userIdField, contentField, embeddingField))
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
     * @param userId 所属用户（记忆按用户隔离，召回时同用户才可见）
     * ⚠️ 记忆是旁路增强：失败只记日志，绝不影响问答主流程
     */
    public void insertMemory(float[] vector, Long userId, String question, String answer) {
        try {
            JsonObject row = new JsonObject();
            row.addProperty("id", memoryIdSeq.incrementAndGet());
            row.addProperty("user_id", userId);
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
     * 召回相关记忆（按向量相似度，expr 按 user_id 过滤实现记忆隔离）
     * @param userId 当前用户（跨用户的记忆不可见）
     * ⚠️ 失败返回空列表（等同"没有记忆"），不抛异常
     */
    public List<SearchResult> searchMemory(float[] vector, int topK, Long userId) {
        try {
            SearchParam.Builder paramBuilder = SearchParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withVectorFieldName("embedding")
                    .withVectors(List.of(toVectorList(vector)))   // 2.5 SDK 要求 List<Float>
                    .withTopK(topK)
                    .withOutFields(List.of("id", "content"))
                    .withMetricType(MetricType.COSINE)
                    .withParams("{\"nprobe\":10}");
            // 记忆按用户隔离：只召回当前用户的历史问答
            if (userId != null) {
                paramBuilder.withExpr("user_id == " + userId);
            }
            SearchParam param = paramBuilder.build();
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
    // 按文档删除向量 + 混合检索
    // ============================================================

    /**
     * 按文档 ID 删除 Milvus 向量：文档删除/增量重解析时的向量级联清理入口。
     * 与 MySQL 删除配套，保证检索层不再召回已删除文档的内容。
     * 【设计要点】按非主键字段删除：expr 布尔表达式 "document_id in [x]"，语法与官方 delete 文档一致
     * 【常见问题】如何确认删除生效？——v1 返回 R&lt;MutationResult&gt;，getDeleteCnt() 应大于 0，防止旧向量残留；
     *   v2 则是 DeleteReq → DeleteResp（io.milvus.v2...response.DeleteResp），两套 API 勿混用；
     *   若担心非主键字段删除的兼容性，兜底可先查 MySQL 拿 chunkIds，再按主键 "id in [...]" 删除
     */
    public void deleteByDocumentId(Long documentId) {

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
     * 混合检索旧签名重载：只有查询向量、没有查询原文，无法走 BM25 稀疏路，退化为纯稠密检索。
     * 【设计要点】BM25 的输入是文本：稀疏向量由服务端对原文分词生成，仅有 queryVector 时稀疏路无从发起
     * 【常见问题】完整双路实现在哪？——见 hybridSearch(String queryText, float[] queryVector, int topK) 重载
     */
    public List<SearchResult> hybridSearch(float[] queryVector, int topK) {
        // 功能：无查询原文只能走稠密路｜要点：BM25 稀疏检索的输入是文本（EmbeddedText）而非向量
        return search(queryVector, topK);
    }

    // ============================================================


    /**
     * 删除主 collection（重建混合索引第 1 步：旧结构无法原地升级 BM25，只能删了重建）
     * ⚠️ 危险操作：删除后向量数据清空，必须紧接着 createHybridCollection() + 重新向量化
     *    （完整流程见 DocumentServiceImpl.rebuildHybridIndex）
     */
    public void dropMainCollection() {
        try {
            milvusServiceClient.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName(collectionName).build());
            log.warn("Milvus collection 已删除: {}（待按 BM25 结构重建并重新向量化）", collectionName);
        } catch (Exception e) {
            log.error("删除 Milvus collection 失败: {}", e.getMessage());
            throw new BusinessException("删除 Milvus collection 失败: " + e.getMessage());
        }
    }

    /**
     * 创建含 BM25 Function 的混合检索 collection（v2 API）：稠密 + 稀疏双路召回的结构基础。
     * 在 rebuildHybridIndex 索引重建流程中调用，替代无稀疏字段的旧结构。
     * 【设计要点】BM25 Function：注册 FunctionType.BM25（输入 content、输出 bm25_vector），
     * 插入时服务端自动对 content 分词生成稀疏向量，现有 v1 insertVectors 无需任何改动
     * 【常见问题】content 为什么必须 enableAnalyzer？——BM25 分词依赖 analyzer，不开则 Function 失效；
     *   稀疏向量是什么？——SparseFloatVector，按词项存储非零权重，与稠密语义向量互补
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
                    .fieldName("document_id").dataType(io.milvus.v2.common.DataType.Int64).build());   // ⚠️ 必须有：现有 v1 insertVectors 会写 document_id，schema 缺该字段插入直接报错；同时是检索层用户隔离（expr 过滤）的过滤字段
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

            // 2) 建表 + 向量索引（稠密 embedding + 稀疏 bm25_vector 都必须建，缺一则 loadCollection 报
            //    "there is no vector index on field"）+ 加载
            milvusClientV2.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName).collectionSchema(schema).build());
            milvusClientV2.createIndex(CreateIndexReq.builder()
                    .collectionName(collectionName)
                    .indexParams(List.of(
                            IndexParam.builder()
                                    .fieldName("embedding")
                                    .indexType(IndexParam.IndexType.AUTOINDEX)
                                    .metricType(IndexParam.MetricType.COSINE)
                                    .build(),
                            IndexParam.builder()
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
                    .collectionName(collectionName)
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