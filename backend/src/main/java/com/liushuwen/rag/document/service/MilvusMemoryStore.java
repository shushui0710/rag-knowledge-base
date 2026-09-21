package com.liushuwen.rag.document.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.liushuwen.rag.config.MilvusConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.*;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.dml.*;
import io.milvus.param.index.*;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 长期记忆库（qa_memory）的独立存储：自己建表、自己读写，与文档向量物理隔离。
 *
 * 职责边界：本类只负责**记忆这一次要链路**——结构、写入、召回，以及与之强绑定的
 * 「按 UTF-8 字节裁剪」这一存储侧不变量（裁剪规则由 Milvus 字段上限决定，放在别处就会失配）。
 * 文档向量（{@link MilvusService}）与集合运维（{@link MilvusCollectionManager}）互不干扰。
 *
 * 【设计要点】记忆是**旁路增强**：写入/召回失败一律降级为"没有记忆"，绝不抛异常打断问答主流程。
 * 【常见问题】为什么单独一个 collection？——记忆与知识库是两类数据（一个是用户历史问答，
 *   一个是文档切片），混在一起会被检索召回互相污染；用 user_id 过滤实现记忆的跨用户隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusMemoryStore {

    private final MilvusServiceClient milvusServiceClient;

    /** Milvus 配置：仅需向量维度（集合名固定为 qa_memory，与主库解耦） */
    private final MilvusConfig milvusConfig;

    /** 记忆专用 collection（避免记忆混入文档检索结果） */
    public static final String MEMORY_COLLECTION = "qa_memory";

    /** 记忆 collection 的用户隔离字段名：expr 里拼字符串极易写错且无处复用，故集中一处 */
    public static final String MEMORY_USER_FIELD = "user_id";

    /** 记忆 content 字段上限（**UTF-8 字节**）：与 ensureMemoryCollection 的 contentField 共用同一常量，
     *  保证「被向量化的文本」与「入库文本」用同一把尺子裁剪，不会一边裁剪一边超限。
     *  公开给 MemoryService 复用：调用方须用同一上限先行裁剪。 */
    public static final int MEMORY_CONTENT_MAX_LEN = 2048;

    /**
     * 记忆主键自增。
     * 【缺陷修复·主键跨重启冲突】原实现初值恒为 1（new AtomicLong(1)），而本字段是 Spring 单例的**实例字段**：
     * 每次应用重启（含每一轮验收测试）计数器都归零，新记忆的 id 又从 2 重新开始 ⇒ 与上一轮运行写入的记忆
     * **主键完全重叠**。Milvus 不强制主键唯一（insert 不报错），但 query/search 阶段按主键去重——
     * 09-20 实测：qa_memory 物理 202 行，其中 id=2 一个主键上压了 38 条记录，id=2..10 共 9 个 id 承载全部记录，
     * 可召回的逻辑实体只剩 9 条，其余被同主键的新版本永久遮蔽 ⇒ 号称"跨会话长期记忆"的能力实际退化为
     * "当前进程生命周期内"。
     * 修法：初值取当前时间戳（毫秒级且单调递增，远大于历史小 id），重启后从新的时间点续写，既不与历史主键
     * 冲突，也**不需要改 schema / 重建 collection**（历史数据原地保留）。
     */
    private final AtomicLong memoryIdSeq = new AtomicLong(System.currentTimeMillis());

    /**
     * 应用启动时准备记忆 collection（不存在则创建）。
     * 【设计要点】与 MilvusCollectionManager 各自独立 @PostConstruct：两个库互不依赖，
     *   谁先建都不影响对方，也就没有初始化顺序问题
     */
    @PostConstruct
    public void init() {
        try {
            ensureMemoryCollection();
        } catch (Exception e) {
            log.warn("Milvus 记忆 collection 初始化失败（可能 Milvus 还没启动）: {}", e.getMessage());
        }
    }

    /**
     * 创建记忆 collection（幂等：已存在则跳过）
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
                    .withName(MEMORY_USER_FIELD).withDataType(DataType.Int64)
                    .build();
            FieldType contentField = FieldType.newBuilder()
                    .withName("content").withDataType(DataType.VarChar)
                    .withMaxLength(MEMORY_CONTENT_MAX_LEN).build();   // ⚠️ 与裁剪常量同源，见 MEMORY_CONTENT_MAX_LEN
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding").withDataType(DataType.FloatVector)
                    .withDimension(milvusConfig.getDimension()).build();
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
     * 保存一条记忆。
     * @param content 记忆正文（约定为 "问题\n回答"）。⚠️ **必须与计算 vector 时喂给 embedding 的文本
     *                完全一致**——二者不一致时，向量只反映文本的一部分，而入库的是另一份内容，
     *                语义相似度与实际存储错位（见 MemoryServiceImpl.saveExchange 的 09-20 修复）。
     * @param userId 所属用户（记忆按用户隔离，召回时同用户才可见）
     * ⚠️ 记忆是旁路增强：失败只记日志，绝不影响问答主流程
     */
    public void insertMemory(float[] vector, Long userId, String content) {
        try {
            // 【缺陷修复·超长内容静默丢失】content 字段 schema 上限 2048，超长时 Milvus 直接报 code=1100
            // "length of varchar field content exceeds max length"（09-20 实测：2600 字必失败、1900 字正常）。
            // 该异常又被本方法 catch 吞掉，只在日志留一行 WARN ⇒ 用户与用例都看不出记忆少了一条。
            // 此处是**第二道防线**：正常调用方（MemoryServiceImpl）已按同一上限先行裁剪，不会走到这里；
            // 仅当有旁路调用方绕过约定时才由本保护兜底，并用 WARN 暴露出来。
            // ⚠️ 陷阱：Milvus 的 max_length 计的是 **UTF-8 字节数**，而 Java 的 String.length() 是 UTF-16
            // 字符数。中文 1 字符 = 3 字节，只按"字符数 ≤ 2048"截断仍会得到 ~6144 字节（首次修复即因此
            // 又被 A4-09 实测打回：日志明确报 length: 6124），故必须按字节截断。
            int contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (contentBytes > MEMORY_CONTENT_MAX_LEN) {
                content = truncateUtf8(content, MEMORY_CONTENT_MAX_LEN);
                log.warn("记忆内容 {} 字节超过 Milvus 字段上限 {} 字节，已按 UTF-8 边界截断入库（前缀={}）",
                        contentBytes, MEMORY_CONTENT_MAX_LEN, preview(content));
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", memoryIdSeq.incrementAndGet());
            row.addProperty(MEMORY_USER_FIELD, userId);
            row.addProperty("content", content);
            JsonArray arr = new JsonArray();
            for (float v : vector) {
                arr.add(v);
            }
            row.add("embedding", arr);
            milvusServiceClient.insert(InsertParam.newBuilder()
                    .withCollectionName(MEMORY_COLLECTION)
                    .withRows(List.of(row))
                    .build());
            log.info("记忆已保存: id={}, 前缀={}", row.get("id").getAsLong(), preview(content));
        } catch (Exception e) {
            log.warn("记忆保存失败（不影响本次回答）: {}", e.getMessage());
        }
    }

    /**
     * 召回相关记忆（按向量相似度，expr 按 user_id 过滤实现记忆隔离）
     * @param userId 当前用户（跨用户的记忆不可见）
     * ⚠️ 失败返回空列表（等同"没有记忆"），不抛异常
     */
    public List<MilvusService.SearchResult> searchMemory(float[] vector, int topK, Long userId) {
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
                paramBuilder.withExpr(fieldEquals(MEMORY_USER_FIELD, userId));
            }
            SearchParam param = paramBuilder.build();
            R<SearchResults> response = milvusServiceClient.search(param);
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<MilvusService.SearchResult> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                MilvusService.SearchResult sr = new MilvusService.SearchResult();
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
     * 拼「字段 == 标量」的 Milvus 过滤表达式。
     * 【设计要点】表达式的唯一构造点：过滤条件若散落各处用字符串拼，字段名写错只会在运行期报解析失败
     * 【常见问题】为什么不直接字符串拼接？——集中一处后字段名可被引用检查，将来统一加转义也只改这里
     */
    public static String fieldEquals(String field, Object value) {
        return field + " == " + value;
    }

    /**
     * 按 **UTF-8 字节上限**截断字符串，且不会把多字节字符切坏。
     * 【常见问题】为什么不直接用 substring(0, maxBytes)？——Milvus VarChar 的 max_length 计的是 UTF-8
     * 字节数，而 Java 的 String.length() 是 UTF-16 字符数，两者对中文差 3 倍；直接按字符数截断必然超限
     * （实测 2048 字符 ≈ 6144 字节，仍被 Milvus 判 code=1100）。
     */
    public static String truncateUtf8(String s, int maxBytes) {
        int end = s.length();
        while (end > 0 && s.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            end--;
        }
        return s.substring(0, end);
    }

    /** 日志用的短前缀（避免把整篇记忆/报告打进日志） */
    private static String preview(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }
}
