package com.liushuwen.rag.document.service;

import com.liushuwen.rag.common.BusinessException;
import com.liushuwen.rag.config.MilvusConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.index.*;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Milvus 集合结构管理器：只管"表长什么样、在不在、怎么换"，不碰任何一行向量数据。
 *
 * 职责边界（与同包两个类合起来构成完整的 Milvus 接入层）：
 *   - 本类：collection 的**生命周期与结构**——建表（v1 稠密结构 / v2 BM25 混合结构）、结构探测、
 *     幂等删建、影子表顶替主表（冷升级切换点）、集合命名；
 *   - {@link MilvusService}：文档向量的**写入 / 检索 / 删除**；
 *   - {@link MilvusMemoryStore}：长期记忆库（qa_memory）的**独立结构与读写**。
 *   【为什么按这个轴拆】这三块的**变更原因完全不同**：结构变（加字段、换索引类型、升级 BM25）
 *   只在索引重建时发生；向量读写是每次问答的热路径；记忆是旁路增强。原先挤在一个 847 行的类里，
 *   热路径与运维路径共享字段与 import，改任何一处都要通读整个类。
 *
 * 【设计要点】v1/v2 双 SDK 共存：v1(MilvusServiceClient) 稳定用于稠密路建表，
 *   v2(MilvusClientV2) 才支持 BM25 Function 与 renameCollection，各取所长。
 * 【常见问题】为什么单独一个类管集合？——结构操作是不可逆的高危动作（drop/rename），
 *   集中在一处才能一行一行看清楚"谁在什么时候删表"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusCollectionManager {

    private final MilvusServiceClient milvusServiceClient;

    /** v2 客户端：BM25 Function 建表、结构探测、影子表改名切换（v1 不支持） */
    private final MilvusClientV2 milvusClientV2;

    /** Milvus 配置：集合名与向量维度（与 MilvusService / MemoryStore 共用同一份强类型配置） */
    private final MilvusConfig milvusConfig;

    /**
     * 应用启动时为**主检索库**准备好结构（不存在则创建）。
     * 【设计要点】@PostConstruct 生命周期：Bean 依赖注入完成后回调，适合启动期资源准备
     * 【常见问题】Milvus 未启动导致初始化失败怎么办？——catch 后仅 log.warn 不阻断应用启动（可用性优先），
     *   待 Milvus 恢复后重启即可补建；为何启动期不重试？——阻塞式重试会拖慢甚至卡死应用上线
     */
    @PostConstruct
    public void init() {
        try {
            ensureCollection();
        } catch (Exception e) {
            log.warn("Milvus 主 collection 初始化失败（可能 Milvus 还没启动）: {}", e.getMessage());
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
        String collectionName = mainCollectionName();
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
            FieldType documentIdField = FieldType.newBuilder()
                    .withName("document_id")
                    .withDataType(DataType.Int64)
                    .build();
            FieldType contentField = FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(2048)
                    .build();
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(milvusConfig.getDimension())
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

    /** 主 collection 名（运维接口与日志用，避免集合名散落到调用方） */
    public String mainCollectionName() {
        return milvusConfig.getCollectionName();
    }

    /** 影子 collection 名：冷升级时先用它承载新结构，回放完成后改名顶替主表 */
    public String shadowCollectionName() {
        return milvusConfig.getCollectionName() + "__rebuild";
    }

    /** 幂等判断 collection 是否存在 */
    public boolean existsCollection(String name) {
        return Boolean.TRUE.equals(milvusClientV2.hasCollection(
                HasCollectionReq.builder().collectionName(name).build()));
    }

    /**
     * 探测主 collection 是否已是 BM25 混合结构（存在 bm25_vector 字段即视为已升级）。
     * 索引重建据此选路径：已升级 ⇒ 原地重灌（零真空期）；未升级 ⇒ 影子表 + 改名切换。
     * 【设计要点】结构事实以服务端 schema 为准，而不是看本地配置或"集合是否存在"——
     *   旧结构 collection 同样存在，仅凭存在性判断会把"需要重建"误判成"无需重建"
     * 【常见问题】探测失败为什么当作"需要重建"？——宁可多走一次安全的影子表切换，
     *   也不能因为探测异常而在结构不对的表上盲插数据
     */
    public boolean mainCollectionHasBm25() {
        String collectionName = mainCollectionName();
        try {
            if (!existsCollection(collectionName)) {
                return false;   // 主表都不在：谈不上"已升级"，走建表逻辑即可（无需删旧）
            }
            DescribeCollectionResp resp = milvusClientV2.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collectionName).build());
            List<String> fields = resp.getFieldNames();
            return fields != null && fields.contains("bm25_vector");
        } catch (Exception e) {
            log.warn("探测 collection 结构失败（按需要重建处理）: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 幂等删除 collection：不存在直接跳过；存在则先卸载再删除。
     * 【设计要点】先 release 再 drop：已加载的集合占着查询节点内存，卸载后再删更干净
     * 【常见问题】卸载失败为什么要继续尝试删除？——卸载失败通常只是"本来就没加载"，
     *   不应因此阻塞真正的删除动作，故卸载单独 try 仅告警
     */
    public void dropCollectionIfExists(String name) {
        if (!existsCollection(name)) {
            return;
        }
        try {
            milvusClientV2.releaseCollection(ReleaseCollectionReq.builder()
                    .collectionName(name).build());
        } catch (Exception e) {
            log.warn("释放 collection 失败（继续尝试删除）: {} - {}", name, e.getMessage());
        }
        try {
            milvusClientV2.dropCollection(DropCollectionReq.builder()
                    .collectionName(name).build());
            log.warn("已删除 collection: {}", name);
        } catch (Exception e) {
            log.error("删除 collection 失败: {} - {}", name, e.getMessage());
            throw new BusinessException("删除 collection 失败: " + e.getMessage());
        }
    }

    /**
     * 影子表顶替主表（冷升级的最后一步，也是唯一的"不可用窗口"）。
     * 顺序：卸载新旧两表 → 删除旧主表腾出名字 → 影子表改名为主表名 → 加载新主表。
     *
     * 【设计要点】把耗时的"全量回放"放在改名之前，于是回放全程旧表照常对外服务；
     *   真正会读到"表不存在"的窗口只有 rename 这几毫秒——对比修复前"先 drop 再回放"，
     *   真空期从整轮回放时长（实测 72s）压缩到毫秒级。
     * 【常见问题】失败会丢数据吗？——不会：影子表已灌满且完好，仅"改名"这一步未完成，
     *   重试本接口时前缀步幂等（影子表会被重建再回放），MySQL 分块始终是事实源
     */
    public void promoteShadowToMain() {
        String collectionName = mainCollectionName();
        String shadow = shadowCollectionName();
        try {
            // 功能：卸载两表——改名要求集合处于未加载状态，避免"已加载"引发改名失败
            releaseQuietly(shadow);
            releaseQuietly(collectionName);
            // 功能：删除旧主表腾出正式名（此时旧结构数据已由影子表全量承接）
            dropCollectionIfExists(collectionName);
            // 功能：影子表改名顶替主表名（切换点）
            milvusClientV2.renameCollection(RenameCollectionReq.builder()
                    .collectionName(shadow)
                    .newCollectionName(collectionName)
                    .build());
            // 功能：加载新主表——Milvus 检索前必须 load，否则查询报"collection not loaded"
            milvusClientV2.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName).build());
            log.warn("索引切换完成: {} → {}（回放期间旧表未中断服务）", shadow, collectionName);
        } catch (Exception e) {
            log.error("索引切换失败（影子表 {} 数据完好，可重试本接口）: {}", shadow, e.getMessage(), e);
            throw new BusinessException("索引切换失败: " + e.getMessage()
                    + "（影子表数据完好，重试本接口即可）");
        }
    }

    /** 静默卸载：失败只告警（通常是"本来就没加载"），不阻断后续流程 */
    private void releaseQuietly(String name) {
        try {
            if (existsCollection(name)) {
                milvusClientV2.releaseCollection(ReleaseCollectionReq.builder()
                        .collectionName(name).build());
            }
        } catch (Exception e) {
            log.warn("卸载 collection 失败（忽略）: {} - {}", name, e.getMessage());
        }
    }

    public void createHybridCollection() {
        createHybridCollection(mainCollectionName());
    }

    /**
     * 创建含 BM25 Function 的混合检索 collection（v2 API）：稠密 + 稀疏双路召回的结构基础。
     * 在索引重建流程中调用：原地重灌路径传主表名，冷升级路径传影子表名。
     * 【设计要点】BM25 Function：注册 FunctionType.BM25（输入 content、输出 bm25_vector），
     * 插入时服务端自动对 content 分词生成稀疏向量，现有 v1 insertVectors 无需任何改动
     * 【常见问题】content 为什么必须 enableAnalyzer？——BM25 分词依赖 analyzer，不开则 Function 失效；
     *   稀疏向量是什么？——SparseFloatVector，按词项存储非零权重，与稠密语义向量互补
     *
     * @param targetName 目标 collection 名（主表或影子表）
     */
    public void createHybridCollection(String targetName) {
        try {
            // 幂等：已存在则跳过（如需用新结构重建，先 drop 旧 collection）
            if (Boolean.TRUE.equals(milvusClientV2.hasCollection(
                    HasCollectionReq.builder().collectionName(targetName).build()))) {
                log.warn("collection 已存在，跳过创建：{}（如需用新结构重建，请先 drop 旧 collection）",
                        targetName);
                return;
            }

            // 1) 定义 Schema（字段 + BM25 Function）
            // ⚠️ 这里必须用 v2 的 DataType（io.milvus.v2.common.DataType）全限定名，
            //    因为本接入层 v1 建表（ensureCollection）用了 io.milvus.grpc.DataType，
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
                    .dimension(milvusConfig.getDimension()).build());                  // 稠密向量（沿用现有）
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
                    .collectionName(targetName).collectionSchema(schema).build());
            milvusClientV2.createIndex(CreateIndexReq.builder()
                    .collectionName(targetName)
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
                    .collectionName(targetName).build());
            log.info("混合检索 collection 创建成功（含 BM25 Function）: {}", targetName);
        } catch (Exception e) {
            log.error("创建混合检索 collection 失败: {}", e.getMessage(), e);
            throw new BusinessException("Milvus collection 初始化失败: " + e.getMessage());
        }
    }
}
