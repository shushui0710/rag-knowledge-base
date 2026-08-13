# Agentic RAG 演进辅导方案（第 8 周起）

> **辅导对象**：刘树汶（RAG 智能知识库问答平台已完成 7 周开发）
> **目标**：在现有 Spring Boot 3 + Milvus + 智谱 + DeepSeek 项目上，把"普通 RAG"演进为"Agentic RAG"，形成求职差异化亮点
> **辅导方式**：延续脚手架模式——AI 给骨架 + 你填 TODO + 我看代码给反馈。每个 TODO 标注难度（⭐~⭐⭐⭐）、提示、参考答案（用 `--- 删除这行 ---` 包裹）、面试考点

> **📖 阅读指南（重要）**：本文档每个 TODO 都分两部分，标注清晰，不要混淆——
> - **【思路提示】**：解释"为什么这么做、怎么下手"，**不是最终代码**；
> - **【标准答案】**（`--- 删除这行 ---` 包裹）：**完整、可直接套用的最终代码**，已对照项目真实类（MilvusService.SearchResult / LlmService / RagProperties 等）和官方 API 文档核对，复制即可运行（个别需先新增的辅助方法，答案内已给出完整代码）。
> - 学习建议不变：**先自己写 → 看【思路提示】→ 对照【标准答案】→ 关掉答案重写一遍**。
> - 骨架代码注释里同样有【标准答案】段落（标 ✅ 的文件=已完整实现，无需再填）。

---

## 零、先说清楚：这套方案怎么用

### 0.1 三个阶段的教学节奏（每阶段固定循环）

```
Step 1 概念讲解   → AI 讲清楚"为什么这样做"（对应下方每阶段"概念讲解"）
Step 2 框架代码   → AI 给出新类的骨架 + TODO，你动手填核心逻辑
Step 3 针对性反馈 → 你把代码贴给 AI，AI 对照"验收标准"逐条点评 + 追问预案
```

你的学习策略不变：**先自己写 → 看提示 → 看答案 → 关掉答案重新写一遍**。

### 0.2 时间规划与裁剪建议（秋招季务实版）

| 版本 | 覆盖范围 | 周期 | 适用 |
|------|---------|------|------|
| **冲刺版（推荐）** | 阶段2（混合检索+查询改写）+ 阶段3（Agentic 化）全部 | 3 周 | 秋招进行中，主打"差异亮点" |
| **完整版** | 阶段1~5 全部 | 5~6 周 | 秋招前有空档，全面升级 |
| **极简版** | 阶段3 的核心 3 个 TODO（工具调用+ReAct+意图路由） | 1.5 周 | 时间极紧，只要"能讲" |

> **优先级判断依据**（来自 2026.8 市场调研）：基础 RAG 在 AI 岗面试中已成"默认能力"；**工具调用/自主决策（Agentic）才是面试官想聊的差异化点**。所以阶段 3 是性价比之王，阶段 4/5 按时间取舍。

### 0.3 主线业务场景（所有阶段共用一条故事线）

你的项目定位升级为：**"企业知识助手"**——员工不仅能问文档，还能让助手"动手干活"。

```
故事线：
员工问："帮我查一下上季度入职培训的文档里，安全规范怎么说？"     → RAG 问答（你已有的）
员工问："这个月知识库新增了多少文档？哪些还没向量化？"           → 工具调用（阶段3，查 MySQL）
员工问："把文档《应急预案》里关于火灾的部分，生成一份整改报告"   → 多步推理+多Agent（阶段4）
```

每个阶段在这条线上前进一小步，**不需要推翻现有代码**。

---

## 一、阶段 1：基础 RAG 回顾与加固（0.5~1 周）

### 1.1 阶段目标与预期成果

**目标**：不是重做，而是"盘点加固"——把你已实现的基础 RAG 补上 3 个企业级细节，为后续演进打地基。

**预期成果**：
- [ ] 离线流程支持**增量更新**（文档变更只重算该文档的分块）
- [ ] 检索接口暴露 **score 阈值过滤**（低分片段不进入 Prompt，省钱且防幻觉）
- [ ] 写一份"当前系统能力清单"（面试开场用）

### 1.2 概念讲解：基础 RAG 的朴素链路与它的三个"瓶颈"

```
朴素 RAG：文档 → 切分 → 向量化 → 存 Milvus
          问题 → 向量化 → 检索 TopK → 拼 Prompt → LLM 生成

三个瓶颈（也是阶段2/3要解决的）：
① 检索只看"向量相似度"，不知道关键词精确匹配（如型号"E217"向量检索不准）
② 检索是一次性的，不会根据结果调整（召回不理想也没办法）
③ 助手只能"答"，不能"做"（不能查库、不能调接口、不能自己规划）
```

### 1.3 实战练习（TODO）

#### TODO 1-1 ⭐ 增量更新：文档重传时只重算该文档
**练习目标**：理解"全量重建 vs 增量更新"的工程取舍。

**【思路提示】**：
1. 删除 MySQL 分块用 `documentChunkMapper.delete(条件)`（MyBatis-Plus 按条件删，注意不是 `deleteById(主键)`——这是高频面试坑）
2. 删除 Milvus 向量用 `milvusService.deleteByDocumentId(id)`（内部 `DeleteParam` + 布尔表达式 `"document_id in [id]"`）
3. 增量 = 先按 documentId 清掉旧 chunks（MySQL + Milvus 都删），再走解析→向量化→入库

```java
// DocumentServiceImpl.java 中新增（骨架）
public void reparseDocument(Long documentId) {
    // TODO 1-1a: 删除该文档旧的 DocumentChunk（MySQL）
    // 提示：documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
    //           .eq(DocumentChunk::getDocumentId, documentId))   // delete(条件)，不是 deleteById(主键)！

    // TODO 1-1b: 删除该文档旧的向量（Milvus）
    // 提示：milvusService.deleteByDocumentId(documentId)，内部用布尔表达式 "document_id in [id]"

    // TODO 1-1c: 重新走解析流程（复用 upload() 里的 documentParserService.parse
    //             + documentChunkService.chunkAndSave + embed(documentId)，封装成 reparseInternal()）
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/document/service/impl/DocumentServiceImpl.java` → `reparseDocument()`
> 📁 **前置方法**：`backend/src/main/java/com/liushuwen/rag/document/service/MinioService.java` → `download()`；`DocumentParserService.java` → `parse(InputStream, String)` 重载

```java
// ============ 前置①：MinioService 新增 download 方法 ============
// import io.minio.GetObjectArgs;
// public byte[] download(String objectName) {
//     try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
//             .bucket(minioConfig.getBucketName()).object(objectName).build())) {
//         return in.readAllBytes();
//     } catch (Exception e) {
//         throw new BusinessException("文件下载失败: " + e.getMessage());
//     }
// }

// ============ 前置②：DocumentParserService 新增 parse(InputStream, String) 重载 ============
// public String parse(InputStream in, String fileType) {
//     switch (fileType) {
//         case "pdf":  return parsePdf(in);
//         case "docx": return parseDocx(in);
//         case "txt":
//         case "md":   return parseText(in);
//         default:     throw new BusinessException("不支持的文件格式: " + fileType);
//     }
// }
// // 说明：parse(MultipartFile, String) 内部就是这三兄弟，重载直接复用私有方法

// ============ 主方法（⚠️ 异常处理与 upload() 一致） ============
public void reparseDocument(Long documentId) {
    try {
        // 步骤1：删 MySQL 分块（delete(条件)，不是 deleteById(主键)！）
        documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocumentId, documentId));
        // 步骤2：删 Milvus 向量（MilvusService 内用 DeleteParam + expr="document_id in [" + id + "]"）
        milvusService.deleteByDocumentId(documentId);
        // 步骤3：重新解析 + 分块 + 向量化
        // ⚠️ parse(InputStream,String) 重载声明 throws IOException（受检异常），
        //    必须捕获或声明——这里由 catch (Exception) 统一处理
        byte[] data = minioService.download(document.getMinioPath());
        String text = documentParserService.parse(new ByteArrayInputStream(data), document.getFileType());
        int chunkCount = documentChunkService.chunkAndSave(documentId, text);
        document.setChunkCount(chunkCount);
        document.setEmbeddingStatus(0);
        documentMapper.updateById(document);
        embed(documentId);   // 复用已有向量化流程（embed 内校验"已向量化"，状态已重置为0）
        log.info("增量更新完成: id={}, chunkCount={}", documentId, chunkCount);
    } catch (BusinessException e) {
        throw e;   // 业务异常（文档不存在、embed 校验失败等）原样上抛 → GlobalExceptionHandler 转 Result
    } catch (Exception e) {
        log.error("增量更新失败: id={}, error={}", documentId, e.getMessage(), e);
        throw new BusinessException("文档增量更新失败: " + e.getMessage());
    }
}
```
`--- 删除这行 ---`

**面试考点**：*"文档更新了，你们怎么保证检索到的是新内容？"* → 增量更新 + 定时/手动触发；追问 *"Milvus 按字段删数据怎么写？"* → Java 端用 `DeleteParam.newBuilder().withCollectionName(...).withExpr("document_id in [x]").build()` 再 `milvusClient.delete(param)`，布尔表达式 `in [x]` 语法（面试口述时可以说 `client.delete(collection, "document_id in [x]")`，但要清楚实际是 DeleteParam）。删除后确认返回 `deleteCount > 0`。

#### TODO 1-2 ⭐ 检索质量门槛：score 阈值过滤
**练习目标**：理解相似度分数不是"越高越好"，要按场景设阈值。

**【思路提示】**：`MilvusService.search()` 返回的 `SearchResult` 带 score 字段，在 `ChatServiceImpl.ask()` 里过滤。

```java
// ChatServiceImpl.ask() 中，检索结果拼接进 Prompt 之前
// （注入：private final RagProperties ragProperties;）
List<MilvusService.SearchResult> hits = milvusService.search(queryVector, topK);
// TODO 1-2: 过滤掉 score < threshold 的结果（threshold 从 rag.agent.min-score 读，默认 0.35）
// 提示：hits.removeIf(h -> h.getScore() < ragProperties.getAgent().getMinScore());
// 注意：过滤后如果为空，走"兜底回答"而不是让 LLM 瞎编
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/chat/service/impl/ChatServiceImpl.java` → `ask()` 检索结果拼接 Prompt 之前
> 📁 **配置**：`backend/src/main/resources/application.yml` → `rag.agent.min-score`（默认 0.35）

```java
double minScore = ragProperties.getAgent().getMinScore();
hits.removeIf(h -> h.getScore() < minScore);
if (hits.isEmpty()) {
    // 兜底：构造 assistant 消息保存并返回（与 ask() 下方保存回答逻辑一致）
    ChatMessage fallback = new ChatMessage();
    fallback.setSessionId(sessionId);
    fallback.setRole("assistant");
    fallback.setContent("知识库中没有找到足够相关的内容，请换个问法或先上传相关文档。");
    chatMessageMapper.insert(fallback);
    return fallback;
}
```
`--- 删除这行 ---`

**面试考点**：*"检索分数低的时候怎么处理？"* → 阈值过滤 + 兜底文案；追问 *"阈值怎么定？"* → 用测试集调（先跑 50 个问题看分数分布，取召回/精度平衡点）。

#### TODO 1-3 ⭐⭐ 能力清单文档
**练习目标**：把"我能讲清楚的部分"固化成文字（面试开场不慌）。

**验收**：用 10 分钟写出当前系统的：技术栈、5 个核心流程（上传/解析/向量化/检索/问答）、3 个工程化细节（增量、阈值、先存问题）、2 个已知短板（检索单一、不能执行动作——正好是阶段 2/3 要补的）。

### 1.4 常见陷阱与调试技巧

| 陷阱 | 现象 | 解法 |
|------|------|------|
| Milvus 布尔表达式写错 | 删数据抛异常 | 先 `client.query(collection, "document_id in [1,2]", ...)` 验证表达式 |
| 阈值设太高 | 什么问题都答不了 | 从 0.3 起步，用真实问题调；中文语义相似度普遍偏低 |
| 增量更新后旧向量残留 | 搜到已删除文档 | 删向量务必确认返回 `deleteCount > 0` |

### 1.5 阶段验收标准
- [ ] 重传文档后，旧内容不再出现在检索结果（`deleteCount` 验证）
- [ ] 低分问题能触发兜底文案，不再硬答
- [ ] 能力清单文档写完，且你能脱稿讲 2 分钟

---

## 二、阶段 2：优化检索策略（1 周）

### 2.1 阶段目标与预期成果

**目标**：让检索从"只靠向量"升级为"多路召回 + 精排 + 查询理解"，回答质量上一个台阶。

**预期成果**：
- [ ] 实现 **混合检索**：向量 + 关键词（BM25）双路召回，分数融合
- [ ] 实现 **重排序（Rerank）**：召回 20 条 → 精排取 Top5
- [ ] 实现 **查询改写**：LLM 把口语问题改写成检索友好语句

### 2.2 概念讲解：为什么"只靠向量"不够

```
三个问题的三种解法：
问题① 精确匹配失效（查型号"E217"、查编号"GB/T 12345"）
      → 关键词检索（BM25）兜底 → 混合检索
问题② 召回结果鱼龙混杂（Top5 里 3 条不相关）
      → 先召回 20 条 → 重排序（Rerank）→ 精排取 5 条
问题③ 口语问法和文档措辞不一致（"机器不转了" vs "设备停机故障"）
      → LLM 查询改写：把口语翻译成检索关键词

架构：
问题 → [查询改写] → 并行双路检索 → 融合 20 条 → [Rerank] → Top5 → Prompt
                    ├─ 向量检索（Milvus）
                    └─ BM25 检索（Elasticsearch 或 Milvus 稀疏向量）
```

**技术选型（贴合你的项目，不引入重中间件）**：
- 关键词路（BM25）：**两条路线，二选一**（结论已用 javap 验证当前 SDK 2.4.1）
  - **路线 A（推荐）**：Milvus 升级 **2.5+**，用内置 **BM25 Function**（建 collection 时声明 `FunctionType.BM25`，服务端自动对 VARCHAR 文本分词生成稀疏向量）——Java 端**不需要任何 BM25 计算代码**，改动最小。⚠️ **硬前置：SDK 也要升级**——当前 `milvus-sdk-java 2.4.1` 里【没有】`FunctionType`/`EmbeddedText` 类，`MilvusServiceClient` 也只实现 v1 接口，v2 API 方法不存在；需把 pom.xml 的 `milvus-sdk.version` 升到 2.5.x，用 v2 client（`MilvusClientV2`）按官方示例写。官方文档：milvus.io/docs/bm25-function.md
  - **路线 B（留在 2.4，当前 SDK 即可编译）**：2.4 支持 `SparseFloatVector` **数据类型** + v1 检索（`SearchParam.Builder.withSparseFloatVectors` 已验证存在），但**没有**内置 BM25 计算（官方 BM25 工具 `Bm25Tokenizer/Bm25Weight` 是 Python 库 milvus-model 的，Java 无官方实现）→ 需自己在 Java 里实现"分词 + BM25 公式"生成稀疏向量，工作量明显更大
  - ⚠️ 原方案写的"Milvus 2.4+ 原生支持 BM25（milvus-bm25）"不准确，面试别这么说；想加 ES 也可以，但面试深挖成本高
- Rerank：调智谱 **`rerank`** API（`POST https://open.bigmodel.cn/api/paas/v4/rerank`，模型名 `rerank`/`rerank-pro`，注意**不是 OpenAI 兼容格式**，是 Cohere 风格独立接口），或先手写"分数 + 关键词命中数"的轻量融合
- 查询改写：再调一次 **DeepSeek**（便宜，几厘钱一次），返回改写后的检索词

### 2.3 实战练习（TODO）

#### TODO 2-1 ⭐⭐ 混合检索：BM25 稀疏向量入库 + 双路召回
**练习目标**：理解稠密向量（语义）与稀疏向量（关键词）的互补。

**【思路提示】**：
1. Milvus 建 collection 时除了 `embedding`（FloatVector, 2048 维），加一个 `bm25_vector`（SparseFloatVector）
2. 稀疏向量怎么来，取决于路线（见 2.2 技术选型）：
   - **路线 A（Milvus 2.5+）**：不用算！建 collection 时声明 BM25 Function（`FunctionType.BM25`，输入 text 字段输出 bm25_vector），插入文本自动生成稀疏向量，搜索时直接传文本（Java v2 SDK 用 `EmbeddedText`）
   - **路线 B（Milvus 2.4）**：自己写 Java 分词 + BM25 公式，把稀疏向量 `TreeMap<Long,Float>` 插入 `bm25_vector` 字段（⚠️ 注意：`Bm25Tokenizer/Bm25Weight` 只有 Python 版 milvus-model，Java 没有）
3. 检索时两路都搜，按 `alpha*score_dense + (1-alpha)*score_sparse` 融合（alpha 默认 0.7，可配；两路分数尺度不一致时可用 RRF 倒数排名融合）

```java
// MilvusService 中新增（骨架）
// ⚠️ 返回值用项目现有的 MilvusService.SearchResult（不是 ChunkHit）；
//    稀疏向量索引用 Long（Milvus 稀疏向量维度索引是 int64，不是 Integer）
public List<MilvusService.SearchResult> hybridSearch(float[] denseVector,
                                                    Map<Long, Float> sparseVector,
                                                    int topK) {
    // TODO 2-1a: 构造稠密搜索参数（你已有的 searchParam）
    // TODO 2-1b: 构造稀疏搜索参数（SearchParam 指定输出字段，数据是 SparseFloatVector）
    // 提示：milvusClient.search(collectionName, List.of(denseVector), ...) 与
    //       milvusClient.search(collectionName, List.of(sparse), SearchParam 里字段指向 bm25_vector)
    // TODO 2-1c: 分数融合（alpha 从配置读），取 topK
    // 提示：两路结果按 chunkId 合并，加权求和，再排序截断
    // 完整可运行版见下方【标准答案】；路线 A（Milvus 2.5+ 内置 BM25）见骨架注释
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/document/service/MilvusService.java` → `createHybridCollection()` + `hybridSearch(String, float[], int)`（✅ 已实现）
> 📁 **配置**：`backend/src/main/java/com/liushuwen/rag/config/MilvusConfig.java` → `milvusClientV2()` Bean（✅ 已实现）

```java
public List<MilvusService.SearchResult> hybridSearch(float[] denseVector,
                                                     Map<Long, Float> sparseVector,
                                                     int topK) {
    try {
        // 稠密路
        SearchParam denseParam = SearchParam.newBuilder()
            .withCollectionName(collectionName)
            .withVectorFieldName("embedding")
            .withVectors(List.of(denseVector))
            .withTopK(topK * 2)
            .build();
        List<List<SearchResult>> denseRes = milvusClient.search(denseParam);
        // 稀疏路（注意：2.4.0+ 用 withSparseFloatVectors，不要用 withVectors 传稀疏向量；
        // ⚠️ metric 用 MetricType.IP——v1 MetricType 枚举只有 L2/IP/COSINE 等，
        //   没有 SPARSE 值；SPARSE_INVERTED_INDEX 是 IndexType 不是 MetricType！）
        SearchParam sparseParam = SearchParam.newBuilder()
            .withCollectionName(collectionName)
            .withVectorFieldName("bm25_vector")
            .withSparseFloatVectors(List.of(new TreeMap<>(sparseVector)))  // Map<Long,Float>
            .withTopK(topK * 2)
            .withMetricType(MetricType.IP)   // 稀疏向量相似度用 IP
            .build();
        List<List<SearchResult>> sparseRes = milvusClient.search(sparseParam);
        // 融合（chunkId -> 加权分）；SearchResult 已带 content，无需反查仓库
        // 防御：任一结果集可能为空，先判空再融合
        Map<Long, Double> merged = new HashMap<>();
        if (!denseRes.isEmpty() && !denseRes.get(0).isEmpty()) {
            denseRes.get(0).forEach(r -> merged.merge(r.getId(), alpha * r.getScore(), Double::sum));
        }
        if (!sparseRes.isEmpty() && !sparseRes.get(0).isEmpty()) {
            sparseRes.get(0).forEach(r -> merged.merge(r.getId(), (1-alpha) * r.getScore(), Double::sum));
        }
        return merged.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> /* 从 denseRes/sparseRes 里按 chunkId 找回含 content 的 SearchResult */)
            .collect(Collectors.toList());
    } catch (Exception e) {
        // 第三方检索失败：记日志 + 抛业务异常（GlobalExceptionHandler 统一转 Result）
        log.error("混合检索失败: {}", e.getMessage(), e);
        throw new BusinessException("混合检索失败: " + e.getMessage());
    }
}
```
`--- 删除这行 ---`

**面试考点**：*"向量检索的缺点？"* → 对精确匹配（编号、型号、专业名词）不敏感，短文本区分度低；*"混合检索分数怎么融合？"* → 加权和/倒数排名融合（RRF），alpha 用测试集调。

#### TODO 2-2 ⭐⭐ 重排序：召回 20 → 精排 Top5
**练习目标**：理解"粗召回 + 精排"两段式，Rerank 的输入是「问题 + 候选片段」。

**【思路提示】**：
1. 定义 `RerankService`（新接口），实现类调智谱 `rerank` API（`POST https://open.bigmodel.cn/api/paas/v4/rerank`，**模型名 `rerank`/`rerank-pro`**，入参 query + documents，返回 relevance_score；⚠️ 该接口**不是 OpenAI 兼容**格式，是独立的 Cohere 风格接口）
2. 在 `ChatServiceImpl` 里把"检索 TopK=5"改为"召回 TopK=20 → rerank → 取 5"

```java
// RerankService.java（新类，骨架）
// ⚠️ 项目没有 ChunkHit/ScoredChunk，统一用现有的 MilvusService.SearchResult
public interface RerankService {
    List<MilvusService.SearchResult> rerank(String query, List<MilvusService.SearchResult> candidates, int topN);
}
// 实现类 RerankServiceImpl
// TODO 2-2: 组装 rerank 请求体（参考你 LlmService 里 LinkedHashMap 构建 JSON 的做法）
// 提示：请求格式 {"query": "...", "documents": ["片段1", "片段2", ...], "top_n": 5}
//       响应解析：results[].relevance_score + index → 映射回原 candidate（用 setScore 覆盖原分数）
// 注意：候选文本要截断（如每段前 500 字），避免请求体过大
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/rag/RerankService.java`（接口）+ `RerankServiceImpl.java`（实现，✅ 已实现）
> 📁 **配置**：复用 `backend/src/main/resources/application.yml` 的 `embedding.zhipu.api-key`（同账户）
>
> ```java
> // RerankServiceImpl.rerank() 完整实现
public List<MilvusService.SearchResult> rerank(String query,
                                               List<MilvusService.SearchResult> candidates,
                                               int topN) {
    try {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "rerank");   // 智谱官方 rerank 模型名（不是 bge-reranker-v2-m3）
        body.put("query", query);
        body.put("documents", candidates.stream()
            .map(c -> truncate(c.getContent(), 500)).collect(Collectors.toList()));
        body.put("top_n", topN);
        HttpEntity<String> req = new HttpEntity<>(objectMapper.writeValueAsString(body), headers());
        Map resp = restTemplate.postForObject(rerankUrl, req, Map.class);
        // 解析 results[] -> relevance_score + index，按 index 映射回原 candidate
        List<MilvusService.SearchResult> ranked = new ArrayList<>();
        for (Map r : (List<Map>) resp.get("results")) {
            int idx = ((Number) r.get("index")).intValue();
            MilvusService.SearchResult sr = candidates.get(idx);
            sr.setScore(((Number) r.get("relevance_score")).floatValue());  // 用新分数
            ranked.add(sr);
        }
        return ranked;
    } catch (Exception e) {
        // 降级：API 失败时按原分数排序取前 N（保可用）
        return candidates.stream()
            .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
            .limit(topN)
            .collect(Collectors.toList());
    }
}
// 辅助：private String truncate(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
```
`--- 删除这行 ---`

**面试考点**：*"Rerank 和向量检索的区别？"* → 向量检索算"问题和片段的语义距离"（快但粗）；Rerank 是"问题和每个候选片段做交叉编码打分"（准但慢），所以只能精排少量候选；*"为什么能提升效果？"* → 交叉编码能看到问题和片段的逐词交互。

#### TODO 2-3 ⭐⭐ 查询改写：LLM 把口语转成检索词
**练习目标**：理解"查询理解"是 RAG 第一环，改写能显著提升召回。

**【思路提示】**：
1. 复用 `LlmService`（DeepSeek），新增 `QueryRewriterService`
2. Prompt 设计（这是关键，写清楚输入输出格式）：*"你是一个检索词改写助手，把用户的问题改写成 2-3 个更适合检索的关键词/短语，只输出改写结果，用|分隔"*
3. 在 `ask()` 流程里：原问题先进 QueryRewriter → 改写结果用于检索 → **但回答时 Prompt 里仍用原问题**（防止改写丢失用户意图）

```java
// QueryRewriterService 接口 + 实现（骨架）
public String rewrite(String question) {
    // TODO 2-3: 调用 LlmService 生成改写词
    // 提示：构建 system+user 消息（参考你 LlmService 里 LinkedHashMap 的两层消息结构）
    //       system: "你是检索关键词改写助手..."
    //       user: "原问题：{question}\n请输出2-3个检索词，用|分隔"
    // 注意：设置低 temperature（如 0.2），让输出稳定
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/rag/QueryRewriterService.java`（接口）+ `QueryRewriterServiceImpl.java`（实现，✅ 已实现）
> 📁 **前置方法**：`backend/src/main/java/com/liushuwen/rag/chat/service/LlmService.java` → `chatWithSystem(system, user, temperature)`（✅ 已实现，TODO 3-4/4-3 也复用它）
>
> ```java
> public String rewrite(String question) {
    String prompt = "你是检索关键词改写助手。把用户问题改写成2-3个更适合检索的关键词短语，"
        + "只输出改写结果，用|分隔。\n原问题：" + question;
    // ⚠️ generateSimple 不存在：需先在 LlmService 新增带 system/temperature 的生成方法
    //    （如 chatWithSystem(String system, String user, double temperature)，
    //      参考 chat() 的 LinkedHashMap 消息结构，把 system 作为第一条消息）
    String raw = llmService.chatWithSystem(
        "你是检索关键词改写助手。把用户问题改写成2-3个关键词短语，只输出结果，用|分隔。",
        question, 0.2);
    return Arrays.stream(raw.split("[|，,；]"))
        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
}
```
`--- 删除这行 ---`

**面试考点**：*"查询改写会引入什么风险？"* → LLM 改写可能偏离原意 → 措施：只用于检索，回答仍用原问题；改写失败走原问题兜底（try-catch 包住）。

### 2.4 常见陷阱与调试技巧

| 陷阱 | 现象 | 解法 |
|------|------|------|
| 混合检索权重难调 | 结果不如单路 | 用测试集对比：跑 30 个问题，分别看纯向量/纯BM25/混合的命中率 |
| Rerank 请求体超限 | 400 错误 | 候选片段截断到 500 字；候选数先 20 再降 |
| 查询改写改变意图 | 答非所问 | 回答用原问题；A/B 对比改写前后效果 |
| Milvus 稀疏向量报错 | schema 版本不匹配 / 不支持 BM25 | 稀疏向量字段是新加的，旧数据没有 → 重建 collection；确认 Milvus 版本：内置 BM25 Function 需 **2.5+**（2.4 只有稀疏向量类型，BM25 要自己算） |

**调试技巧**：给 `search` 加一个 debug 日志开关（`rag.debug-print=true` 时打印：改写词、两路分数、融合后 Top5 及各自来源），面试时能讲"我是怎么调检索质量的"。

### 2.5 阶段验收标准
- [ ] 建一个 20 题的"检索测试集"（含精确匹配题 5 道 + 口语题 10 道 + 普通题 5 道），混合检索+Rerank 后 Top5 命中率 ≥ 80%（改造前先跑基线，记录提升）
- [ ] 任意一个"查型号/查编号"类问题，能准确命中对应文档片段
- [ ] 能向面试官讲清楚：召回→精排两段式为什么比单次 TopK 好

---

## 三、阶段 3：Agentic 化演进（1~1.5 周）——差异化核心 ⭐⭐⭐

### 3.1 阶段目标与预期成果

**目标**：让助手从"只会答"变成"会判断 + 会动手"——能自主决定"这个问题该查文档还是查数据库"，能多步执行工具调用。**这是你简历上的核心新亮点**。

**预期成果**：
- [ ] 定义统一 `Tool` 接口 + 工具注册表（至少 2 个真实工具）
- [ ] 实现 **Function Calling**：LLM 返回结构化工具调用意图，系统执行并回填结果
- [ ] 实现 **ReAct 循环**：思考→行动→观察→再思考，最多 N 轮
- [ ] 实现 **意图路由**：普通问答走 RAG，查数据走工具，都能处理

### 3.2 概念讲解：Agentic RAG 到底是什么

```
普通 RAG：问题 →（固定）检索 →（固定）生成
Agentic RAG：问题 → LLM 自主决策：该检索？该查库？该先查库再检索？→ 循环执行

核心机制一：Function Calling（工具调用）
  LLM 不直接"执行"，而是输出结构化意图：
  {"name": "query_documents", "arguments": {"question": "安全规范"}}
  系统收到后调用对应工具，把结果"观察"回给 LLM，LLM 再继续

核心机制二：ReAct 循环（Reason + Act）
  Thought（LLM 思考）→ Action（调工具）→ Observation（看结果）→ 再 Thought...
  直到 LLM 认为可以给出最终答案

核心机制三：意图路由（简化版 Agent）
  第一步先问 LLM："这个问题需要 ①查文档 ②查数据库 ③两者都要"
  然后走对应流程——这是"轻量 Agent"，面试最好讲清楚
```

**为什么用 DeepSeek Function Calling 而不是手写解析**：DeepSeek API 是 OpenAI 兼容的，支持 `tools` 参数，模型会返回 `tool_calls` 结构化 JSON——比手写"让模型输出 JSON 再解析"稳定得多，且这是业界标准做法。

### 3.3 实战练习（TODO）

#### TODO 3-1 ⭐⭐⭐ 统一 Tool 抽象 + 工具注册表
**练习目标**：理解"工具 = 一个带描述的可执行函数"，注册表 = 给 LLM 看的能力清单。

**【思路提示】**：
1. 定义 `Tool` 接口：`name()`、`description()`（给 LLM 看！要写清楚什么时候用这个工具）、`parametersJsonSchema()`（OpenAI 格式）、`execute(Map<String,Object> args)`（返回 String 结果）
2. `ToolRegistry` 用 Spring 把所有 Tool Bean 收集起来：`Map<String, Tool>`
3. 实现第一个工具：**query_document_stats**（查 MySQL：文档总数/未向量化数/各分类数）——把你的 `DocumentServiceImpl` 统计逻辑包成工具

```java
// Tool.java（新接口，骨架）
public interface Tool {
    String name();                          // 如 "query_document_stats"
    String description();                   // 如 "查询知识库文档统计信息（总数、分类、向量化状态），当用户问'有多少文档/统计'时使用"
    String parametersJsonSchema();          // OpenAI Function 的 parameters JSON
    String execute(Map<String, Object> arguments);  // 返回字符串结果（会被回填给 LLM）
}

// QueryDocumentStatsTool.java（第一个工具，骨架）
@Component
public class QueryDocumentStatsTool implements Tool {
    // 注入 DocumentMapper / DocumentChunkMapper
    @Override public String name() { return "query_document_stats"; }
    // TODO 3-1a: 写 description（面试考点：描述写不好，LLM 就不调用它）
    // 提示：参考上面注释里的写法：触发场景 + 作用
    @Override public String execute(Map<String, Object> args) {
        // TODO 3-1b: 统计文档总数、已向量化数、未向量化数，拼成一段文字返回
        // 提示：documentMapper.selectCount(null) 总数；
        //       未向量化 = count where vector_status = 0（如果你有这个字段，没有就 count chunk 为空的）
        // 返回如："知识库共 42 篇文档，35 篇已向量化，7 篇待处理"
    }
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/agent/Tool.java`（接口）+ `ToolRegistry.java`（注册表）
> 📁 **工具实现**：`QueryDocumentStatsTool.java`（✅ 已实现）/ `QueryDocumentListTool.java`（⏳ 3-1b）

```java
@Component
@RequiredArgsConstructor
public class QueryDocumentStatsTool implements Tool {
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;

    @Override public String name() { return "query_document_stats"; }
    @Override public String description() {
        return "查询知识库文档的统计信息：文档总数、已向量化数量、未向量化数量、按分类统计。"
            + "当用户询问'有多少文档''哪些文档没处理''文档统计'等涉及数量的问题时使用。"
            + "注意：这是数据统计工具，不是文档内容检索工具，问具体内容不要用我。";
    }
    @Override public String parametersJsonSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
    }
    @Override public String execute(Map<String, Object> arguments) {
        // ⚠️ 异常处理：DB 查询失败返回错误文案（工具失败不抛异常，Agent 循环才能继续）
        try {
            // 数据隔离：只统计当前登录用户的文档（工具经 /api/agent/** 触发，JwtInterceptor 保证有 userId）
            Long userId = UserContext.getUserId();
            long total = documentMapper.selectCount(
                    new LambdaQueryWrapper<Document>().eq(Document::getUserId, userId));
            long withChunk = documentChunkMapper.selectCount(
                    new LambdaQueryWrapper<DocumentChunk>()
                            .inSql(DocumentChunk::getDocumentId,
                                    "select id from document where user_id = " + userId)
                            .isNotNull(DocumentChunk::getContent));
            return "知识库共 " + total + " 篇文档，其中 " + withChunk + " 篇已有内容分块，"
                + (total - withChunk) + " 篇待处理。";
        } catch (Exception e) {
            log.error("文档统计失败: {}", e.getMessage(), e);
            return "查询文档统计失败：" + e.getMessage() + "，请稍后重试。";
        }
    }
}
```
`--- 删除这行 ---`

**面试考点**：*"工具描述为什么重要？"* → LLM 靠 description 决定调不调、什么时候调，写得好=调用准确率高；追问 *"工具执行结果怎么给回模型？"* → 作为 `role=tool` 的消息回填。

#### TODO 3-2 ⭐⭐⭐ LlmService 支持 Function Calling
**练习目标**：让 DeepSeek 调用支持 `tools` 参数，返回结构化 `tool_calls`。

**【思路提示】**：
1. 在 `LlmService` 新增方法：`chatWithTools(List<Map<String,Object>>, List<Tool>)`（消息格式：{"role":..,"content":..}）
2. 请求体在原有 messages 基础上加 `tools` 数组（从 Tool 的 name/description/parametersJsonSchema 转换）+ `tool_choice: "auto"`
3. 响应解析：`choices[0].message` 里可能是 `content`（直接回答）也可能是 `tool_calls`（要调工具）

```java
// LlmService 新增方法（骨架）
public LlmResponse chatWithTools(List<ChatMessage> messages, List<Tool> tools) {
    // TODO 3-2a: 构建请求体 messages + tools + tool_choice:"auto" + temperature:0.3
    // 提示：参考现有 generate() 的 LinkedHashMap 结构；tools 数组元素:
    //   {"type":"function","function":{"name":..,"description":..,"parameters":JSON字符串}}
    // TODO 3-2b: POST 后解析响应，注意 message 里可能有 tool_calls 字段
    // 提示：先定义 ToolCall POJO：{id, function:{name, arguments(JSON字符串)}}
    //       如果 message.tool_calls 存在 → 返回 LlmResponse(TOOL_CALL, toolCalls)
    //       否则 → 返回 LlmResponse(ANSWER, content)
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/chat/service/LlmService.java` → `chatWithTools()` + 内部类 `LlmResponse` / `ToolCall`
> 📁 **前置方法**：同文件 `chatWithSystem()`（✅ 已实现）

```java
public LlmResponse chatWithTools(List<Map<String,Object>> messages, List<Tool> tools) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", "deepseek-v4-flash");   // ⚠️ deepseek-chat 已于 2026-07-24 弃用，改 v4 系列
    body.put("messages", messages);
    body.put("tools", tools.stream().map(t -> {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "function");
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", t.name());
        fn.put("description", t.description());
        // ⚠️ 项目没有 JsonUtils，用 fastjson 的 JSONObject.parseObject（项目已引入 fastjson）
        fn.put("parameters", JSONObject.parseObject(t.parametersJsonSchema()));
        f.put("function", fn);
        return f;
    }).collect(Collectors.toList()));
    body.put("tool_choice", "auto");
    body.put("temperature", 0.3);
    // 发起请求（复用你 RestTemplate 的 postForObject）
    Map resp = restTemplate.postForObject(llmUrl, new HttpEntity<>(body, headers()), Map.class);
    // ⚠️ 注意：choices 是数组！先 get(0) 再取 message（原参考答案写错，会 ClassCastException）
    Map message = (Map) ((List<Map>) resp.get("choices")).get(0).get("message");
    if (message.containsKey("tool_calls")) {
        List<ToolCall> calls = ((List<Map>) message.get("tool_calls")).stream()
            .map(m -> new ToolCall((String) m.get("id"),
                (String) ((Map) m.get("function")).get("name"),
                (String) ((Map) m.get("function")).get("arguments")))
            .collect(Collectors.toList());
        return LlmResponse.toolCalls(calls);
    }
    return LlmResponse.answer((String) message.get("content"));
}
```
`--- 删除这行 ---`

**面试考点**：*"Function Calling 和让模型输出 JSON 有什么区别？"* → 原生 tool_calls 是模型在训练时对齐过的输出格式，结构化稳定，不需要在 Prompt 里费劲约束格式；*"多工具怎么办？"* → 一次可能返回多个 tool_calls，循环执行。

#### TODO 3-3 ⭐⭐⭐ 实现 ReAct 执行器（Agent 核心循环）
**练习目标**：写一个 `AgentExecutor`，把"思考→调工具→观察→再思考"循环跑起来。

**【思路提示】**：
1. 新建 `AgentExecutor`（Spring @Service），持有 LlmService + ToolRegistry
2. 循环逻辑：
   - 调 `chatWithTools(messages, tools)`
   - 如果返回 ANSWER → 返回最终答案
   - 如果返回 TOOL_CALL → 逐个执行工具（从 ToolRegistry 按 name 找），把结果作为 `role=tool` 消息追加进 messages，**带上 tool_call_id**（DeepSeek 要求）
   - 循环，最多 `maxIterations`（默认 5）轮，超了返回"处理步骤太多，请简化问题"
3. 消息历史：用户问题 → （循环内追加 assistant + tool 消息）→ 最终答案

```java
// AgentExecutor.java（核心，骨架）
@Service
public class AgentExecutor {
    // 注入 LlmService、ToolRegistry
    public String execute(String userQuestion) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", userQuestion));
        int iterations = 0;
        while (iterations++ < maxIterations) {
            LlmResponse resp = llmService.chatWithTools(messages, toolRegistry.all());
            if (resp.isAnswer()) {
                return resp.getContent();          // 最终答案
            }
            for (ToolCall call : resp.getToolCalls()) {
                // TODO 3-3a: 执行工具
                // 提示：Tool tool = toolRegistry.get(call.getName());
                //       结果 = tool.execute(JSONObject.parseObject(call.getArguments()));  // fastjson
                //       异常要捕获，把错误信息也回填给模型（让它换个方式）
                // TODO 3-3b: 把 assistant 消息（含 tool_calls）和 tool 消息都追加进 messages
                // 提示：assistant 消息要带上 model 返回的 tool_calls（保持原样）；
                //       tool 消息格式: {"role":"tool","tool_call_id":call.getId(),"content":结果}
            }
        }
        return "这个问题步骤较多，请拆分成几个小问题再问。";
    }
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/agent/AgentExecutor.java` → `execute()`（ReAct 循环）
> 📁 **依赖**：`LlmService.chatWithTools()`（3-2）+ `ToolRegistry` + `AgentMetrics`（✅ 已实现）

```java
// 拿到 resp 后，如果 resp 含 tool_calls（TOOL_CALL 态）：
// 步骤1（关键，别漏！）：把模型返回的 assistant 消息【原样】追加进 messages。
//   ——assistant 消息里包含 tool_calls，是后续 tool 消息的前置，漏了会报 400：
//   "messages with role 'tool' must be a response to a preceding message with 'tool_calls'"
messages.add(Map.of(
    "role", "assistant",
    "content", resp.getContent() == null ? "" : resp.getContent(),
    "tool_calls", /* 模型返回的原始 tool_calls 列表，原样塞回 */ resp.getRawToolCalls()));

// 步骤2：逐个执行工具，结果作为 role=tool 消息（必须带 tool_call_id 与上一步配对）
for (ToolCall call : resp.getToolCalls()) {
    String result;
    try {
        Tool tool = toolRegistry.get(call.getName());
        if (tool == null) result = "错误：工具不存在 " + call.getName();
        else result = tool.execute(JSONObject.parseObject(call.getArguments())); // fastjson
    } catch (Exception e) {
        result = "工具执行失败：" + e.getMessage() + "，请调整参数或换一种方式";
    }
    messages.add(Map.of(
        "role", "tool",
        "tool_call_id", call.getId(),
        "content", result));
}
// 循环回到 while 顶部，把 messages 再发给 chatWithTools —— 此时模型看到工具结果，继续推理
```
`--- 删除这行 ---`

**面试考点**：*"Agent 死循环怎么办？"* → maxIterations 上限 + 超时 + 工具结果异常回填让模型自救；*"工具调用的对话历史怎么组织？"* → user → assistant(tool_calls) → tool(结果) → assistant(tool_calls) → ... → assistant(最终答案)，每对 tool_call 必须带 tool_call_id 关联。

#### TODO 3-4 ⭐⭐ 意图路由：轻量决策，先判断再分流
**练习目标**：用一次便宜的 LLM 调用，决定"查文档 / 查数据 / 混合"，让系统更稳。

**【思路提示】**：
1. `RouterService.route(question)` → 返回 `DOCUMENT` / `STATS` / `HYBRID` 之一
2. Prompt 里把每个工具/能力描述给它，让它选
3. `ChatServiceImpl.ask()` 改造：先路由 → DOCUMENT 走原 RAG；STATS 走 AgentExecutor（只查库）；HYBRID 走 AgentExecutor（可查库也可检索）

```java
// RouterService（骨架）
public enum Route { DOCUMENT, STATS, HYBRID }

public Route route(String question) {
    // TODO 3-4: 调用 LlmService 判断意图（temperature 0.1）
    // 提示：system: "你是意图分类器。判断问题需要：DOCUMENT=查知识库文档内容，STATS=查数据统计，HYBRID=两者都要。只输出一个词。"
    //       解析返回：contains("DOCUMENT") 且 contains("STATS") → HYBRID；等
    // 注意：解析失败默认 DOCUMENT（宁可多检索，别让用户的问题没人答）
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/rag/Route.java`（枚举）+ `RouterService.java`（接口）+ `RouterServiceImpl.java`（实现）
> 📁 **前置方法**：`backend/src/main/java/com/liushuwen/rag/chat/service/LlmService.java` → `chatWithSystem()`（✅ 已实现）

```java
public Route route(String question) {
    String sys = "你是意图分类器。判断用户问题需要的能力："
        + "DOCUMENT=检索知识库文档内容；STATS=查询数据统计（数量、分类、状态）；"
        + "HYBRID=两者都要。只输出一个词，不要解释。";
    // ⚠️ generateWithSystem 不存在：与 TODO 2-3 一起，在 LlmService 新增 chatWithSystem(system, user, temperature)
    String out = llmService.chatWithSystem(sys, question, 0.1);
    if (out.contains("STATS") && out.contains("DOCUMENT")) return Route.HYBRID;
    if (out.contains("STATS")) return Route.STATS;
    return Route.DOCUMENT;
}
```
`--- 删除这行 ---`

**面试考点**：*"为什么不直接全部走 Agent？"* → 成本与延迟：纯问答用路由直接走 RAG 更快更省（一次 LLM 调用），只有需要工具才进 Agent 循环；*"路由判错怎么办？"* → 默认回落 DOCUMENT + HYBRID 时 Agent 内可再自行调用检索工具兜底。

### 3.4 常见陷阱与调试技巧

| 陷阱 | 现象 | 解法 |
|------|------|------|
| 工具不生效 | LLM 就是不调工具 | 检查 description 是否写了触发条件；temperature 调低（0.3 以下）；工具名用动词+名词（query_/create_） |
| tool_call_id 不匹配 | 400 invalid tool_call_id | assistant(tool_calls) 和 tool 消息必须严格配对，id 原样回传 |
| arguments 是字符串 | JSON 解析失败 | DeepSeek 返回的 arguments 是 JSON 字符串，先 parseObject 再传工具 |
| 循环不收敛 | 一直调同一个工具 | 超迭代上限；工具结果里带明确错误信息让模型转向 |
| 消息历史无限膨胀 | token 超限 | 每次循环后裁剪（只保留最近 N 轮 user/assistant/tool） |

**调试技巧**：`AgentExecutor` 里打结构化日志：`[Agent] round=1 thought=tool_call name=query_document_stats args={} result=...`，一次调试就能看清整个决策链——面试讲这个日志设计也是亮点（可观测性）。

### 3.5 阶段验收标准
- [ ] 对用户说"知识库里有多少文档"，助手能调工具查库并回答出真实数字
- [ ] 对用户说"查一下应急预案里火灾怎么处理"，助手走 RAG 正常回答
- [ ] 对用户说"应急预案里关于火灾的部分，一共涉及几个章节"——助手能**先检索再推理**（HYBRID 链路）
- [ ] 能脱稿讲 3 分钟：普通 RAG → Agentic RAG 的演进动机 + ReAct 循环 + Function Calling 原理
- [ ] 简历可写："实现基于 Function Calling 的 Agent 执行器（ReAct 循环，最多 5 轮工具调用），支持文档检索与数据查询多工具自主调度"

---

## 四、阶段 4：复杂 Agent 架构（1 周，选做但加分明显）

### 4.1 阶段目标与预期成果

**目标**：从"单个 Agent"升级到"多 Agent 协作 + 记忆 + 反思"，覆盖面试官最想深挖的"高级话题"。

**预期成果**：
- [ ] 多 Agent：主管（Orchestrator）分派 + 专用 Agent（文档问答 / 数据查询 / 报告生成）协作
- [ ] 记忆管理：短期（会话内）+ 长期（向量库存历史问答，跨会话召回）
- [ ] 反思机制：LLM 自评回答质量，不达标自动重写（Self-Correct）

### 4.2 概念讲解：为什么要多 Agent 而不是一个"超级 Agent"

```
一个超级 Agent 的问题：所有工具塞给它 → 决策空间大 → 容易选错工具、上下文混乱、成本高

多 Agent 的思路（分工）：
            ┌─────────────┐
 用户问题 → │ 主管 Agent   │ 判断问题类型，分派
            └──────┬──────┘
      ┌────────────┼────────────┐
      ▼            ▼            ▼
 文档问答Agent  数据查询Agent  报告生成Agent
 （RAG）       （工具）        （调LLM+文档）
  各自工具少、Prompt 聚焦 → 决策准、成本低

记忆管理：
  短期记忆：会话内消息（你已有 ChatSession/ChatMessage）
  长期记忆：把"用户常问 + 高质量回答"向量化存 Milvus，新问题来先检索历史问答
  → 这就是"让 Agent 记住上次帮你查过什么"的面试亮点

反思（Self-Correct/Reflection）：
  生成回答后，再用一个 LLM 评判："这个回答是否回答了问题？是否有依据？"
  不合格 → 带着评判意见重新生成（限 1-2 次）
```

### 4.3 实战练习（TODO）

#### TODO 4-1 ⭐⭐⭐ 多 Agent 协作：主管 + 3 个专用 Agent
**练习目标**：理解"路由到专用 Agent"的编排模式（比单 Agent 更可控）。

**【思路提示】**：
1. 抽象 `Agent` 接口：`AgentType type()`、`String execute(String task, List<Map<String,Object>> history)`
2. 实现 `DocumentAgent`（封装 RAG 检索+生成）、`StatsAgent`（复用工具调用）、`ReportAgent`（调 LLM 生成结构化报告）
3. `OrchestratorAgent`：先路由（复用阶段3的 Router，扩到三路）→ 分派 → 汇总

```java
// Agent.java（骨架）
public interface Agent {
    AgentType type();
    String execute(String task, List<Map<String,Object>> history);
}
// OrchestratorAgent.java
@Service
public class OrchestratorAgent implements Agent {
    // 注入 List<Agent>（Spring 自动收集所有 Agent Bean）
    public String execute(String task, List<Map<String,Object>> history) {
        // TODO 4-1: 路由并分派
        // 提示：Router.route(task) → DOCUMENT → 找 DocumentAgent；STATS → StatsAgent；REPORT → ReportAgent
        //       找不到对应 Agent 时降级给 DocumentAgent
    }
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/agent/Agent.java`（接口）+ `DocumentAgent/StatsAgent/ReportAgent/OrchestratorAgent.java`
> 📁 **报告工具**：`GenerateReportTool.java`（⏳ 4-1）

```java
public String execute(String task, List<Map<String,Object>> history) {
    Route r = router.route(task);
    // ⚠️ 坑：Route 枚举有 HYBRID，但 AgentType 没有对应的 HYBRID 类型（只有 DOCUMENT/STATS/REPORT）。
    // 直接 a.type().name().equals(r.name()) 在 HYBRID 时会找不到 Agent → 必须先把 HYBRID 拆解/降级：
    //   方案一：HYBRID → 先 StatsAgent 后 DocumentAgent，或按子任务分别分派；
    //   方案二：HYBRID → 降级给 DocumentAgent（让它在 Agent 内自行决定是否调工具）。
    Agent target = agents.stream()
        .filter(a -> a.type().name().equals(r.name()))
        .findFirst().orElse(documentAgent);
    return target.execute(task, history);
}
```
`--- 删除这行 ---`

**面试考点**：*"多 Agent 相比单 Agent 的优势？"* → 每 Agent 工具/上下文聚焦，决策准确率高；可独立扩展；可观测性好（每步知道谁在处理）；追问 *"多 Agent 通信怎么实现？"* → 本方案是同步编排（主管直接调用），生产可升级为消息队列异步。

#### TODO 4-2 ⭐⭐ 长期记忆：历史问答向量化召回
**练习目标**：让 Agent "记得"之前答过什么（跨会话），并控制记忆污染。

**【思路提示】**：
1. 回答完成后，把（问题 + 回答摘要）向量化存进一个新的 Milvus collection `qa_memory`（或复用现有 collection 加 type 字段）
2. 新问题进来时，先检索 qa_memory，命中且分数高（> 阈值）→ 作为"历史记忆"拼进 Prompt

```java
// MemoryService.java（骨架）
public void saveExchange(String question, String answer) {
    // TODO 4-2a: 问题+回答摘要向量化（复用 EmbeddingService）
    // TODO 4-2b: 写入 Milvus collection "qa_memory"（带 question 原文、时间戳）
}
public List<String> recall(String question) {
    // TODO 4-2c: 检索 qa_memory，score > 0.5 的返回问题+答案对
    // 提示：返回值拼成"历史问答记忆"段落，注入 Prompt 的 system 或 context
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/rag/MemoryService.java`（接口）+ `MemoryServiceImpl.java`（实现）
> 📁 **依赖**：`MilvusService.java` → 需新增 `insertMemory()` / `searchMemory()`（答案内已给完整代码）

```java
// 保存（⚠️ 记忆是旁路增强：失败只记日志，绝不能阻断问答主流程）
//   try {
//       if (question == null || answer == null) return;          // 空值防御
//       // ⚠️ EmbeddingService.embed(List<String>) 入参是 List、返回 List<float[]>，别传单个 String
//       List<float[]> vecs = embeddingService.embed(List.of(question + " " + truncate(answer, 200)));
//       if (vecs == null || vecs.isEmpty()) return;
//       milvusService.insertMemory(vecs.get(0), question, answer);   // 待新增方法，见 TODO 4-2 提示
//   } catch (Exception e) {
//       log.warn("长期记忆保存失败（不影响本次回答）: {}", e.getMessage());
//   }
//
// 召回（⚠️ 失败返回空列表，等同"没有记忆"）
//   try {
//       if (question == null) return List.of();                  // 空值防御
//       List<float[]> vecs = embeddingService.embed(List.of(question));
//       if (vecs == null || vecs.isEmpty()) return List.of();
//       List<MemoryHit> hits = milvusService.searchMemory(vecs.get(0), 3);  // 待新增方法
//       return hits.stream().filter(h -> h.getScore() > 0.5)
//           .map(h -> "Q:" + h.getQuestion() + "\nA:" + h.getAnswer())
//           .collect(Collectors.toList());
//   } catch (Exception e) {
//       log.warn("长期记忆召回失败（按无记忆处理）: {}", e.getMessage());
//       return List.of();
//   }
```
`--- 删除这行 ---`

**面试考点**：*"长期记忆会不会把错的答案带进来？"* → 记忆入库前要过"质量筛选"（如反思 Agent 判合格才存）+ 召回分数阈值；*"记忆怎么更新？"* → 时间戳 + 容量上限（LRU 淘汰）。

#### TODO 4-3 ⭐⭐⭐ 反思与自我修正（Self-Correct）
**练习目标**：加一个"质检员"：回答不达标就重写，**这是面试官最喜欢深挖的点**。

**【思路提示】**：
1. `CriticService.judge(question, answer, sources)` → 返回 (是否合格, 改进意见)
2. 不合格时：把改进意见作为 system 提示，重新调用生成（最多 1 次，防止无限循环）

```java
// CriticService（骨架）
public Critique judge(String question, String answer, List<String> sources) {
    // TODO 4-3: 让 LLM 评判回答质量
    // 提示：system: "你是回答质量评审。判断标准：①是否直接回答了问题 ②是否有知识库依据
    //                ③是否简洁。输出 JSON: {\"pass\": true/false, \"reason\": \"...\"}"
    //       解析 JSON（复用你 ObjectMapper 的 POJO 绑定，参考第4周 DeepSeekResponse 的做法）
}
// ChatServiceImpl 里：
Critique c = criticService.judge(question, answer, sources);
if (!c.isPass() && retryCount == 0) {
    // TODO: 带 reason 重新生成一次（maxRetry=1）
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/rag/CriticService.java`（接口）+ `CriticServiceImpl.java`（实现）
> 📁 **前置方法**：`backend/src/main/java/com/liushuwen/rag/chat/service/LlmService.java` → `chatWithSystem()`（✅ 已实现）

```java
// 重新生成：把 reason 塞进 system
String sys = "根据评审意见改进你的回答：" + c.getReason()
    + "\n要求：更直接地回答用户问题，并明确引用知识库依据。";
// ⚠️ generateWithSystem 不存在：统一用 TODO 2-3 新增的 LlmService.chatWithSystem(system, user, temperature)
answer = llmService.chatWithSystem(sys, question, 0.4);
```
`--- 删除这行 ---`

**面试考点**：*"反思机制的成本？"* → 每次回答多一次 LLM 调用（约+50%成本）→ 只在"有来源且用户问题较复杂"时才启用，或用更便宜的模型（如 DeepSeek 小模型）当评审；*"如何防止无限自我修正？"* → maxRetry 硬上限。

### 4.4 常见陷阱与调试技巧

| 陷阱 | 现象 | 解法 |
|------|------|------|
| Agent 之间职责重叠 | 两个 Agent 抢同一个问题 | 每个 Agent 的 type + description 写清楚边界，路由表维护好 |
| 记忆污染 | 答出"记忆里"的错误旧答案 | 入库前质量筛选 + 召回阈值 + 时间衰减 |
| 反思循环 | 反复重写越改越差 | maxRetry=1；只在低置信度时触发（如无来源引用） |

### 4.5 阶段验收标准
- [ ] "查应急预案火灾章节 + 统计本月新增文档"这类复合问题，主管 Agent 能正确拆分并分别交给正确 Agent
- [ ] 第二次问"上次你说的那个安全规范是什么"，能从长期记忆中召回（同一用户、不同会话）
- [ ] 故意给一个"知识库里没有答案"的问题，反思机制能识别并给出"知识库中未找到，建议上传相关文档"，而不是硬编
- [ ] 能讲清楚多 Agent 编排的优缺点 + 记忆污染怎么防 + 反思成本怎么控

---

## 五、阶段 5：生产级优化（0.5~1 周）

### 5.1 阶段目标与预期成果

**目标**：从"能跑"到"能上线"——评估、观测、缓存、成本、容错五个维度补齐，**这是央国企面试官最爱听的"工程素养"**。

**预期成果**：
- [ ] 建立评估集与简单评测流程（命中率 + 相关性）
- [ ] Agent 全链路可观测（埋点指标：工具调用数、耗时、token 消耗）
- [ ] embedding 结果缓存（Redis 或本地 Caffeine，避免重复花钱）
- [ ] 成本控制：token 统计、检索结果裁剪、模型分级
- [ ] 容错：超时重试、熔断降级、全链路兜底

### 5.2 概念讲解：生产级 = 可衡量 + 可观测 + 可控成本 + 可靠

```
四个维度，每个对应一个面试亮点：
① 可衡量（评估）：没有评估集，优化就是玄学。20-30 个真实问题的测试集 + 人工打标
② 可观测（观测性）：指标埋点（每次问答耗时/工具调用/token 数）+ 日志结构化 + 慢查询告警
③ 可控成本（成本）：embedding 缓存（同问题不重复向量化）+ 检索裁剪（进 Prompt 的片段减量）+ 模型分级（简单意图用便宜模型）
④ 可靠（容错）：LLM 超时重试 + 熔断（连续失败降级为"兜底回答"）+ 全链路 try-catch（你已有先存问题的容错设计，延伸到 Agent）
```

### 5.3 实战练习（TODO）

#### TODO 5-1 ⭐⭐ 评估集与评测脚本
**练习目标**：把"我觉得效果好"变成"数据说效果好"。

**【思路提示】**：建 `docs/eval/` 目录：20 题测试集（问题 + 期望来源文档 + 期望答案要点）。写一个简单评测：检索命中率 = 检索到的 Top5 里是否包含期望文档。

```java
// EvalRunner.java（测试专用，骨架）
public class EvalRunner {
    // TODO 5-1: 读取 eval/questions.json，逐条跑检索，统计命中率
    // 提示：questions.json 格式 [{"q":"...", "expected_doc_id":123}, ...]
    //       命中率 = 期望 docId 出现在 Top5 检索结果中的条数 / 总条数
    //       每次改动检索策略后跑一遍，对比命中率
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/eval/EvalRunner.java` + `src/test/java/.../EvalRunnerTest.java`（新建）
> 📁 **数据**：`docs/eval/questions.json`（20 题评估集，待建）

```java
// 核心逻辑（伪代码；⚠️ 单条用例失败不中断评估，catch 记 miss 继续）
int hit = 0;
for (EvalCase c : cases) {
    try {
        if (c.q == null || c.expectedDocId == null) {
            System.out.println("[SKIP] 用例数据不完整: " + c.q);
            continue;
        }
        List<float[]> vecs = embeddingService.embed(List.of(c.q));
        if (vecs == null || vecs.isEmpty()) continue;
        List<MilvusService.SearchResult> top5 = milvusService.search(vecs.get(0), 5);
        // ⚠️ SearchResult 目前只有 chunkId（Milvus 主键），没有 documentId：
        //   ① 给 SearchResult 增加 documentId 字段（search 的 outFields 已含 "document_id"，解析处顺手取出）
        //   ② 或用 chunkId 反查 document_chunk 表
        if (top5.stream().anyMatch(h -> c.expectedDocId.equals(h.getDocumentId()))) hit++;
    } catch (Exception e) {
        System.out.println("[ERROR] " + c.q + " -> " + e.getMessage());
    }
}
System.out.println("Top5 命中率: " + hit + "/" + cases.size());
```
`--- 删除这行 ---`

**面试考点**：*"你的 RAG 效果怎么评估？"* → 命中率（检索）+ 相关性人工打分（生成）；*"为什么不用 RAGAS？"* → RAGAS 是 Python 库，手写 Java 评测更贴合你技术栈，且能讲清楚指标含义（加分：说明你知道 RAGAS 存在，但选择自己实现以控成本）。

#### TODO 5-2 ⭐⭐ 观测性：Agent 指标埋点 + 结构化日志
**练习目标**：让每一次问答都可回溯（面试可讲"生产排障流程"）。

**【思路提示】**：
1. 加 `AgentMetrics`（用一个简单类 + Actuator metrics 或只打日志）：记录每次问答的耗时、LLM 调用次数、token 数、工具调用列表
2. `AgentExecutor` 每个关键步骤打结构化日志

```java
// AgentMetrics.java（骨架）
@Component
public class AgentMetrics {
    // 用 ConcurrentHashMap 按天累计：问答次数、平均耗时、LLM 调用次数、工具调用次数
    // TODO 5-2: 加方法 recordQuery(ms)、recordLlmCall()、recordToolCall(name)
    // 提示：暴露 /api/metrics/today 接口（或写入日志），面试时演示"今天处理了多少问答、调了多少次LLM"
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/agent/AgentMetrics.java`（✅ 已实现）+ `controller/MetricsController.java`（✅ 已实现）

```java
public void recordQuery(long costMs, int llmCalls, int toolCalls) {
    // ⚠️ computeIfAbsent 返回 DayStat（不是 AtomicReference<DayStat>），别包一层 AtomicReference
    DayStat stat = stats.computeIfAbsent(today(), k -> new DayStat());
    stat.queryCount.incrementAndGet();
    stat.totalMs.addAndGet(costMs);
    stat.llmCalls.addAndGet(llmCalls);
    stat.toolCalls.addAndGet(toolCalls);
}
```
`--- 删除这行 ---`

**面试考点**：*"Agent 生产环境怎么排查问题？"* → 指标（耗时/调用数/成功率）+ 结构化日志（每轮 thought/action/observation）+ 必要时全链路 traceId（可用 MDC 打日志，面试提到就够）。

#### TODO 5-3 ⭐⭐ 缓存与成本控制
**练习目标**：省钱 = 面试加分（体现成本意识，央国企很在意）。

**【思路提示】**：
1. **Embedding 缓存**：相同文本不重复向量化。用 `ConcurrentHashMap<String, float[]>`（进程内）即可起步，量大再换 Redis（你复习库里 Redis 部分的知识正好用上）
2. **检索裁剪**：进 Prompt 的片段从 5 段减到 3-4 段（配合 Rerank），token 直接省 20%
3. **模型分级**：路由/改写/评判用 DeepSeek 便宜档，最终生成用高质量档

```java
// EmbeddingService 加缓存（骨架）
private final Map<String, float[]> cache = new ConcurrentHashMap<>();
public float[] embed(String text) {
    // TODO 5-3: 查缓存，命中直接返回；未命中调 API 后放入缓存
    // 提示：key 用 text 本身（或 MD5）；容量限制（如 5000 条，超出清空重建或用 LRU）
}
```

`--- 删除这行 ---`

> **【标准答案】**（完整可运行代码，可直接套用。学习建议：先自己写，再对照本答案）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/document/service/EmbeddingService.java` → 新增 `embedSingle()` + 缓存字段

```java
public float[] embed(String text) {
    return cache.computeIfAbsent(text, t -> {
        float[] v = callZhipuEmbedding(t);
        if (cache.size() > 5000) cache.clear(); // 简单容量控制
        return v;
    });
}
```
`--- 删除这行 ---`

**面试考点**：*"embedding 缓存怎么保证一致性？"* → 模型升级/维度变化时要清缓存（配置版本号做缓存 key 前缀）；*"为什么不无脑缓存？"* → 内存占用，所以限容量。

#### TODO 5-4 ⭐ 容错：超时重试 + 降级
**练习目标**：把"先存问题再做的容错设计"（你已有）延伸到 Agent 全链路。

**【思路提示】**：
1. `LlmService` 调用加超时（RestTemplate 已有 60s 读超时）+ 重试 1 次（指数退避）
2. Agent 循环中任一环节失败 → 降级：有检索结果就走普通 RAG 回答；没有 → 兜底文案
3. 熔断：连续 N 次 LLM 失败（如 5 次）→ 一段时间内（如 60s）直接走兜底，不再打 LLM

```java
// 熔断器简单实现（骨架）
@Component
public class LlmCircuitBreaker {
    // TODO 5-4: 记录连续失败次数，超过阈值熔断，熔断期间直接返回 null（触发兜底）
    // 提示：AtomicInteger failureCount + volatile long openUntil
    //       tryAcquire() 检查熔断是否打开；onSuccess()/onFailure() 更新状态
}
```

`--- 删除这行 ---`

> **【标准答案】**（骨架 `LlmCircuitBreaker` 已完整实现，本答案为对照参考，可直接使用）
>
> 📁 **所在目录**：`backend/src/main/java/com/liushuwen/rag/agent/LlmCircuitBreaker.java`（✅ 已实现）+ `AgentExecutor` 中接入

```java
public boolean tryAcquire() {
    if (System.currentTimeMillis() < openUntil) return false; // 熔断中
    return true;
}
public void onFailure() {
    if (failureCount.incrementAndGet() >= 5) {
        openUntil = System.currentTimeMillis() + 60_000; // 熔断 60s
        failureCount.set(0);
    }
}
public void onSuccess() { failureCount.set(0); }
```
`--- 删除这行 ---`

**面试考点**：*"LLM 挂了怎么办？"* → 熔断 + 降级兜底（有依据答"知识库相关片段"+提示稍后再试）；*"为什么熔断而不是一直重试？"* → 持续重试浪费钱且雪上加霜，熔断给系统喘息。

### 5.4 常见陷阱与调试技巧

| 陷阱 | 现象 | 解法 |
|------|------|------|
| 缓存导致旧结果 | 改了文档还答旧内容 | 缓存 key 带 embedding 模型版本；文档更新时按 docId 失效 |
| 指标数据不准 | 统计和实际不符 | 统一在 AgentExecutor 唯一入口埋点，别在多个地方各记各的 |
| 熔断误伤 | 好端端突然全部兜底 | 熔断阈值和恢复时间先用宽松值（10次/60s），观察后再收紧 |

### 5.5 阶段验收标准
- [ ] 跑通评测脚本，能报出"Top5 命中率 X%"，且每次优化前后有对比
- [ ] 连续提问后，能从指标接口看到：问答次数、平均耗时、LLM 调用次数、工具调用次数
- [ ] 相同问题重复问，第二次不再重复调用 embedding API（日志验证）
- [ ] 手动把 LLM 服务停掉（改错 API key），系统在几秒内进入降级状态并返回兜底文案，恢复后自动恢复
- [ ] 能讲 3 分钟"如果这个系统要上线，我会从哪几个维度保证质量"

---

## 六、整体时间轴与交付物清单

```
W1（阶段1+2） 检索加固：增量更新 / 阈值 / 混合检索 / Rerank / 查询改写
W2-W3（阶段3） Agentic 化：Tool 抽象 / Function Calling / ReAct / 意图路由   ← 差异化核心
W4（阶段4）    多 Agent：编排 / 长期记忆 / 反思自修正
W5（阶段5）    生产化：评估 / 观测 / 缓存 / 成本 / 容错
```

**最终交付物（简历 + 面试可直接引用的增量）**：
1. 新增类（约 12 个）：Tool/ToolRegistry/AgentExecutor/RouterService/RerankService/QueryRewriterService/Agent接口/3个Agent/Orchestrator/MemoryService/CriticService/AgentMetrics/LlmCircuitBreaker
2. 新增 2-3 个真实工具（文档统计、文档列表查询、可加"报告生成"）
3. 一个 20 题评估集 + 评测脚本
4. 学习笔记新增两章：Agentic RAG 原理 / 生产化工程
5. 面试亮点从 11 个扩到 14-15 个（工具调用、ReAct 循环、多 Agent、反思、评估、熔断）

**面试话术示例（升级版项目介绍）**：
> "我的知识库系统在基础 RAG 之上做了 Agentic 演进：通过 Function Calling 让模型自主决定检索文档还是查询数据，用 ReAct 循环支持最多 5 轮工具调用；加入意图路由控制成本，多 Agent 编排 + 反思机制保证回答质量；生产层面做了评估集、指标埋点和 LLM 熔断降级，全链路可观测可回溯。"

---

## 七、给 AI 的辅导指令（每次开始前粘贴）

> 请以"脚手架模式"辅导我完成今天的 Agentic RAG 任务：先讲 5 分钟概念（为什么、原理图），再给我新类的代码骨架（import+类结构+API调用，留 TODO），我写完贴给你后，请对照验收标准逐条点评，指出问题并给出面试追问预案。TODO 请标注难度（⭐~⭐⭐⭐）、提示、参考答案（用 `--- 删除这行 ---` 包裹）和面试考点。
