# Agentic RAG 辅导方案 · 逐项事实审查报告

> 审查对象：`docs/AgenticRAG演进辅导方案.md`（全部 14 个 TODO）
> 审查时间：2026-08-12
> 审查方法：逐条技术断言核对 DeepSeek / Milvus / 智谱 / MyBatis-Plus 官方文档，并对照项目真实代码（backend 68 个源文件）
> 结论分级：✅ 已验证正确 · ⚠️ 部分正确需修正 · ❌ 错误/虚构 · 🔶 经验值（无官方标准，需自测校准）

---

## 零、全局性发现（影响多个 TODO，先看这个）

### G1. DeepSeek 模型名已变更（影响 TODO 3-2 / 3-4 / 4-3 及项目配置）
- **事实**：DeepSeek 官方 API 文档（api-docs.deepseek.com）明确：`deepseek-chat` 与 `deepseek-reasoner` 两个模型名**已于 2026-07-24 弃用**，现模型为 `deepseek-v4-flash`（非思考模式，等价原 deepseek-chat）与 `deepseek-v4-pro`。
- **影响**：辅导方案所有参考答案里的 `"model": "deepseek-chat"`，以及项目 `application.yml` 的 `llm.deepseek.model: deepseek-chat` 都建议更新为 `deepseek-v4-flash`（你的问答场景）或 `deepseek-v4-pro`（复杂推理）。
- **来源**：https://api-docs.deepseek.com/zh-cn（"model*  deepseek-chat (将于 2026/07/24 弃用)"）
- **备注**：弃用后"出于兼容考虑仍可用"，所以现在不报错；但面试和演示时应说新模型名。

### G2. "Milvus 2.4+ 原生支持 BM25（milvus-bm25）"表述不准确（影响 TODO 2-1，最重要）
- **事实链**：
  1. Milvus 2.4 支持 `SparseFloatVector` 数据类型（稀疏向量可插入、可检索）——✅ 确认
  2. 但 **BM25 稀疏向量的"生成"工具** `Bm25Tokenizer` / `Bm25Weight` 来自官方 Python 库 `milvus-model`（pymilvus 生态），**Java 端没有官方同名实现** —— 方案提示里的 Java 代码 `Bm25Tokenizer tok = new Bm25Tokenizer(); Bm25Weight weight = Bm25Weight.create(...)` 是**把 Python 类名当成 Java 类写了（虚构 API）** ❌
  3. Milvus **2.5+ 才有内置 BM25 Function**（`FunctionType.BM25`，collection schema 里声明，服务端自动对 VARCHAR 文本分词生成稀疏向量，索引 `MetricType.BM25`，Java SDK 支持）—— 需把 Milvus 升级到 2.5+
- **结论**：TODO 2-1 有两条可行路线，二选一：
  - **路线 A（推荐，改动小）**：Milvus 升级 2.5+，用内置 BM25 Function（官方 `bm25-function.md`），Java 侧不再需要任何 BM25 计算代码；
  - **路线 B（留在 2.4）**：自己实现 BM25 稀疏向量（Java 分词器如 HanLP/jieba + 手写 BM25 公式），插入 `SparseFloatVector` 字段——工作量明显更大。
- **来源**：https://milvus.io/docs/bm25-function.md 、https://milvus.io/docs/sparse_vector.md 、milvus-model（Python）文档
- **备注**：方案里"复用现有 Milvus 不用装 ES"的选型思路本身没问题，但"2.4+ 原生支持"的说法必须修正为"2.5+ 内置 BM25 Function / 2.4 仅支持稀疏向量需自算"。

### G3. 智谱 Rerank 的模型名与"OpenAI 兼容"说法有误（影响 TODO 2-2）
- **模型名**：智谱官方 API 的 rerank 模型名是 **`rerank`**（另有 `rerank-pro`），不是 `bge-reranker-v2-m3`（那是 BAAI 开源模型名，可用于第三方聚合平台，不是智谱 API 的 model 参数值）。
- **兼容性**：智谱 rerank 接口**不是 OpenAI 兼容格式**（OpenAI 根本没有 rerank 端点；该接口是 Cohere 风格的独立 API）。方案原文"OpenAI 兼容：POST /rerank" ❌。
- **接口确认**：`POST https://open.bigmodel.cn/api/paas/v4/rerank`，Body：`model` / `query`（≤4096 字符）/ `documents`（≤128 条，单条 ≤4096 字符）/ `top_n` / `return_documents` / `return_raw_scores`；响应 `results[]` 含 `index`、`relevance_score`、`document`、`usage`。
- **来源**：https://docs.bigmodel.cn/api-reference/模型-api/文本重排序
- **备注**：方案建议"候选片段截断 500 字"合理（官方单条上限 4096 字符，500 字是保守且稳妥的经验值）。

### G4. 参考答案大量引用"项目里不存在的类/方法"（需先新增，不能照抄）
| 方案中引用 | 实际状态 | 替代方案 |
|---|---|---|
| `JsonUtils.parseObject(...)` | ❌ 项目无此类 | 用 fastjson `JSONObject.parseObject(...)`（项目已有 fastjson）或 `ObjectMapper.readValue` |
| `llmService.generateSimple(...)` / `generateWithSystem(...)` | ❌ LlmService 只有 `chat(String)` 和 `chatWithTools(String, List<Tool>)` | 新增一个 `chatWithSystem(String system, String user, double temperature)` 方法，或把 system 指令拼进 user 消息走 `chat()` |
| `documentChunkService.removeByDocumentId(...)` | ❌ DocumentChunkService 只有 `chunkAndSave` | 用 `documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id))` |
| `parseAndSave(documentId)` | ❌ 项目无此方法 | 复用 `documentParserService.parse` + `documentChunkService.chunkAndSave` + `embed(id)` |
| `milvusService.insertMemory(...)` / `searchMemory(...)` | ❌ MilvusService 无此方法 | 按 TODO 4-2 要求自行新增（或复用 `insertVectors` / `search`） |
| `h.getDocId()` | ❌ `SearchResult` 只有 `chunkId` | 给 `SearchResult` 加 `documentId` 字段（search 的 outFields 已含 `document_id`），或用 chunkId 反查 |
| `ChunkHit` / `ScoredChunk` / `LlmResponse` / `List<Message>` | ❌ 方案假设类 | 落地时用现有 `MilvusService.SearchResult`，`LlmResponse` 按 TODO 3-2 自行定义，对话消息用 `List<Map<String,Object>>` |

---

## 一、阶段 1：基础 RAG 加固

### TODO 1-1 增量更新 ⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 增量 vs 全量概念 | ✅ | 工程常识，正确 |
| 删 MySQL 分块 | ⚠️ | 正确思路，但参考答案 `documentChunkService.removeByDocumentId(...)` 方法不存在。**标准做法**：`documentChunkMapper.delete(new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, id))`（MyBatis-Plus `delete(条件)`，注意不是 `deleteById(主键)`） |
| 删 Milvus 向量 | ✅ | `milvusService.deleteByDocumentId(id)` 内部用 `DeleteParam.newBuilder().withCollectionName(...).withExpr("document_id in [" + id + "]").build()`；`milvusClient.delete(param)`。布尔表达式 `in [x]` 语法 ✅（Milvus 官方 delete 文档示例 `"color in ['red_7025', ...]"`）。注意：面试口述的 `client.delete(collectionName, expr)` 是描述性写法，实际 Java 是 DeleteParam |
| 面试考点 `document_id in [x]` | ⚠️ | 表达式语法正确；**不确定点**：Milvus Java v2.2.x 的 delete() API 文档曾注明"只支持 pk_field in [...]"，虽 2.4 实际已放开非主键字段过滤（官方 delete-entities 文档示例即非主键字段），但为 100% 稳妥可在实现时**先用 MySQL 查该文档的 chunkId 列表，再按主键 `id in [chunkIds]` 删除**（双保险，也更快）。核实途径：跑通后看返回 `deleteCount > 0` |
| 重建逻辑 | ⚠️ | 参考答案 `parseAndSave(documentId)` 不存在。**标准流程**：`documentParserService.parse`（需先从 MinIO 取文件流，参考 `upload()` 的实现）→ `documentChunkService.chunkAndSave` → `embed(id)` |

### TODO 1-2 score 阈值过滤 ⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| COSINE 分数范围 | ✅ | Milvus COSINE 相似度 ∈ [-1, 1]（官方 metric 文档）；项目已用 `MetricType.COSINE` |
| `hits.removeIf(h -> h.getScore() < minScore)` | ✅ | Java 代码正确；`SearchResult` 有 `getScore()` |
| 默认 0.35、中文语义分数偏低 | 🔶 | **经验值，无官方标准**。0.3~0.5 是社区常用区间，但必须用你的测试集校准（跑 50 个真实问题看分数分布）。不确定来源：不同 embedding 模型分数分布差异大（bge-large-zh vs 智谱 embedding-3） |
| 兜底文案而非硬答 | ✅ | 最佳实践，正确 |

### TODO 1-3 能力清单 ⭐⭐
✅ 纯文档写作任务，无技术事实风险。

---

## 二、阶段 2：优化检索策略

### TODO 2-1 混合检索 BM25 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| "Milvus 2.4+ 原生支持 BM25（milvus-bm25）" | ❌ | 见 **G2**：2.4 仅支持稀疏向量类型；BM25 生成工具仅 Python 版；**BM25 Function 需 Milvus 2.5+** |
| Java 提示 `Bm25Tokenizer` / `Bm25Weight` | ❌ | **虚构 API**（Python milvus-model 的类）。路线 A：升级 2.5+ 用 `FunctionType.BM25`（服务端自动分词，Java 无需 BM25 代码）；路线 B：2.4 自实现 BM25 |
| 稀疏向量字段类型 | ✅ | `SparseFloatVector`（官方 sparse_vector.md）；Java v1 SDK 2.4.0+ 提供 `withSparseFloatVectors(List<TreeMap<Long,Float>>)`（SearchParam/SearchIteratorParam） |
| 参考答案 `withVectors(List.of(sparseVector))` | ⚠️ | 稀疏路应使用 `withSparseFloatVectors(...)`（`withVectors` 面向稠密向量，混用有类型歧义） |
| 融合公式 `alpha*score_dense + (1-alpha)*score_sparse` | ✅ | 加权和是标准做法之一；另一种是 **RRF（倒数排名融合）** `1/(k+rank)`，对两路分数尺度不一致更鲁棒（面试可提） |
| alpha 默认 0.7 可配 | 🔶 | 经验值，需用测试集调 |
| 参考答案 `chunkRepo.findById` | ❌ | 项目无 chunkRepo；落地时从 SearchResult 拿 content（Milvus 已存 content 字段，无需反查） |

### TODO 2-2 Rerank ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 调用智谱 rerank | ✅ | 路径 `POST https://open.bigmodel.cn/api/paas/v4/rerank` |
| 模型名 `bge-reranker-v2-m3` | ❌ | 智谱官方模型名是 **`rerank`**（或 `rerank-pro`），见 **G3** |
| "OpenAI 兼容" | ❌ | 非 OpenAI 兼容，是 Cohere 风格独立 API，见 **G3** |
| 请求体 `{query, documents, top_n}` | ✅ | 官方参数确认（documents ≤128 条/单条 ≤4096 字符） |
| 响应 `results[].relevance_score + index` | ✅ | 官方响应结构确认 |
| 截断 500 字 | ✅ | 合理（官方上限 4096） |
| 参考答案 Map 解析 | ✅ | 通用写法可用 |

### TODO 2-3 查询改写 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 改写用于检索、回答用原问题 | ✅ | 检索增强的标准最佳实践（防止改写丢意图） |
| 参考答案 `llmService.generateSimple(prompt, 0.2)` | ❌ | 方法不存在，见 **G4**：需新增带 system 的方法或拼接消息 |
| `split("[|，,；]")` 解析 | ✅ | Java 正则正确（| 在中括号内是字面量，无需转义） |
| temperature 0.2 | ✅ | DeepSeek temperature 范围 0~2，0.2 偏保守合理 |
| try-catch 兜底原问题 | ✅ | 最佳实践 |

---

## 三、阶段 3：Agentic 化 ⭐⭐⭐

### TODO 3-1 Tool 抽象 ⭐⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| Tool 接口四方法（name/description/parametersJsonSchema/execute） | ✅ | 与 OpenAI/DeepSeek function calling 模型对齐；项目骨架已落地 |
| `parametersJsonSchema` 返回 `{"type":"object","properties":{},"required":[]}` | ✅ | OpenAI JSON Schema 格式确认 |
| 工具描述重要（LLM 靠 description 决策） | ✅ | DeepSeek 官方 API 文档："A description of what the function does, used by the model to choose when and how to call the function" |
| `documentMapper.selectCount(null)` | ✅ | MyBatis-Plus 合法（统计全表） |
| role=tool 消息回填 | ✅ | DeepSeek 官方 tool calls 流程确认 |

### TODO 3-2 Function Calling ⭐⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| DeepSeek 支持 tools / tool_choice / tool_calls | ✅ | 官方 API 文档确认：`tools`（仅支持 function 类型，最多 128 个）、`tool_choice`（none/auto/required/指定函数）、响应 `message.tool_calls` |
| `tool_choice: "auto"` | ✅ | 官方支持 |
| 请求体 `{"type":"function","function":{name,description,parameters}}` | ✅ | OpenAI 兼容格式确认 |
| tool_calls 结构 `{id, function:{name, arguments(JSON字符串)}}` | ✅ | 官方确认；**arguments 是 JSON 字符串**，调用工具前必须 parse |
| **参考答案 bug：`((Map) resp.get("choices")).get("message")`** | ❌ | **choices 是数组**，正确写法：`((List<Map>) resp.get("choices")).get(0)` 再 `.get("message")`。参考答案注释里自己都写了"choices 是 List，先取 [0]"，代码与注释矛盾——照抄会 ClassCastException |
| `"model": "deepseek-chat"` | ⚠️ | 模型名已弃用，改 `deepseek-v4-flash`/`deepseek-v4-pro`，见 **G1** |
| LlmResponse（ANSWER/TOOL_CALL 两态） | 🔶 | 方案设计类，需自行定义（骨架注释已给出建议结构） |
| `JsonUtils.parseObject` | ❌ | 不存在，用 fastjson/Jackson，见 **G4** |

### TODO 3-3 ReAct 执行器 ⭐⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 循环结构（ANSWER→返回 / TOOL_CALL→执行工具） | ✅ | 标准 ReAct 模式 |
| 消息历史顺序 user → assistant(tool_calls) → tool(结果) → ... | ✅ | DeepSeek/OpenAI 工具调用规范："tool 消息必须匹配前置 assistant 消息的 tool_calls，且带 tool_call_id" |
| **参考答案缺 assistant(tool_calls) 消息回填** | ❌ | **关键 bug**：参考答案只 `messages.add(tool 消息)`，没有把"模型返回的那条 assistant 消息（含 tool_calls）"原样追加进 messages。若 messages 中 tool 消息没有前置 assistant(tool_calls)，DeepSeek 会返回 400（"messages with role 'tool' must be a response to a preceding message with 'tool_calls'"）。**标准做法**：每次循环里 ① 把模型返回的 assistant 消息对象（含 tool_calls）整体 add 进 messages ② 再逐条 add tool 结果消息 |
| tool_call_id 配对 | ✅ | 必须原样回传 `call.getId()` |
| 工具不存在 → 错误信息回填 | ✅ | 好实践（让模型自救） |
| 工具执行异常 → 错误回填 | ✅ | 好实践 |
| maxIterations=5 | 🔶 | 经验值合理，可配 |
| 参考答案 `call.getName()` / `call.getArguments()` / `call.getId()` | ⚠️ | 依赖你自定义的 ToolCall 类字段设计（若 ToolCall 只有 id/function 两个字段，则取名为 `call.getFunction().getName()`）；骨架代码已用 `ToolCall.Function` 嵌套结构，保持一致 |

### TODO 3-4 意图路由 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 一次廉价 LLM 调用分类 | ✅ | 合理架构 |
| temperature 0.1 | ✅ | 合理 |
| contains("STATS") && contains("DOCUMENT") → HYBRID 等 | ✅ | 简单可靠的解析策略 |
| 解析失败默认 DOCUMENT | ✅ | 合理降级 |
| `llmService.generateWithSystem(...)` | ❌ | 不存在，见 **G4** |

---

## 四、阶段 4：复杂 Agent 架构

### TODO 4-1 多 Agent ⭐⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 主管分派 + 专用 Agent | ✅ | 常见编排模式 |
| 同步编排 vs 消息队列异步 | ✅ | 概念正确（本方案同步调用即可） |
| 参考答案 `a.type().name().equals(r.name())` | ⚠️ | 前提是 Route 枚举与 AgentType 枚举的 name 一致（项目骨架中 Route 有 DOCUMENT/STATS/HYBRID，AgentType 有 DOCUMENT/STATS/REPORT——HYBRID 没有对应 AgentType，需在路由时先拆解或降级，别直接 name 匹配，否则 HYBRID 永远找不到 Agent） |

### TODO 4-2 长期记忆 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 概念（问答对向量化存 Milvus，新问题先检索） | ✅ | 通用记忆方案 |
| **参考答案 `float[] vec = embeddingService.embed(question + " " + ...)`** | ❌ | **编译错误**：项目 `EmbeddingService.embed(List<String>)` 入参是 List、返回 `List<float[]>`。正确：`List<float[]> vecs = embeddingService.embed(List.of(question + " " + answer)); float[] vec = vecs.get(0);` |
| `milvusService.insertMemory / searchMemory` | ❌ | 不存在，需新增，见 **G4** |
| 召回阈值 score > 0.5 | 🔶 | 经验值；COSINE 下 0.5 偏严，建议用测试集校准（0.3~0.5 试） |
| 入库前质量筛选 + LRU 淘汰 | ✅ | 最佳实践，正确 |

### TODO 4-3 反思自修正 ⭐⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| LLM 评判输出 JSON（pass/reason） | ✅ | 可行；注意 DeepSeek 输出 JSON 建议配 `response_format={"type":"json_object"}`（官方支持）或靠 POJO 绑定容错 |
| 不合格带 reason 重写 | ✅ | 标准 Self-Correct 模式 |
| maxRetry=1 防无限循环 | ✅ | 正确 |
| 参考答案 `llmService.generateWithSystem(...)` | ❌ | 不存在，见 **G4** |
| "反思约 +50% 成本" | 🔶 | 每次重写 = 多 1 次 LLM 调用，但"50%"是粗略估计；实际取决于生成 token 长度。面试表述建议说"约翻倍"或"额外一次调用" |

---

## 五、阶段 5：生产级优化

### TODO 5-1 评估集 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 命中率评估思路 | ✅ | 正确（Top5 命中率） |
| **参考答案 `h.getDocId()`** | ❌ | `SearchResult` 没有该方法（只有 `chunkId`），见 **G4** |
| 参考答案 `hybridSearch(embed(c.q), bm25(c.q), 5)` | ⚠️ | 依赖 TODO 2-1 的 BM25 实现（见 **G2**）；且方案版 hybridSearch 是 `(float[], Map<Integer,Float>, int)` 三参，骨架落地版是 `(float[], int)`——实现时以你最终定的签名为准 |
| RAGAS 是 Python 库 | ✅ | 事实正确（ragas 为 Python 评估库） |

### TODO 5-2 观测性 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| ConcurrentHashMap 按天累计 | ✅ | 正确（项目骨架 AgentMetrics 已实现） |
| **参考答案 `AtomicReference<DayStat> stat = stats.computeIfAbsent(...)`** | ❌ | **编译错误**：`computeIfAbsent` 返回 `DayStat`（不是 `AtomicReference<DayStat>`）。正确：`DayStat stat = stats.computeIfAbsent(today(), k -> new DayStat());`（骨架落地版已是正确写法） |
| 统一在 AgentExecutor 入口埋点 | ✅ | 好实践 |
| MDC traceId | ✅ | 面试提及即可，概念正确 |

### TODO 5-3 缓存与成本 ⭐⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| `cache.computeIfAbsent(text, t -> {...})` | ✅ | 正确 |
| 容量 5000 + clear | ✅ | 简单可行（面试说明是简化方案即可） |
| 缓存 key 带模型版本 | ✅ | 最佳实践（模型升级/维度变化时自动失效） |

### TODO 5-4 容错 ⭐⭐
| 项 | 结论 | 标准答案与依据 |
|---|---|---|
| 熔断三态概念 | ✅ | 关闭/打开/半开，正确 |
| `tryAcquire` / `onFailure` / `onSuccess` 实现 | ✅ | 正确（与骨架落地版一致） |
| 阈值 5 次 / 60s | 🔶 | 经验值合理；生产可先用宽松值再收紧（文档自己也提到） |
| 重试用指数退避 | ✅ | 标准实践 |

---

## 六、结论汇总

### 必须修正的硬错误（照抄会编译失败/运行报错）
1. **TODO 3-2 参考答案**：`((Map) resp.get("choices")).get("message")` → choices 是 List，先 `.get(0)`
2. **TODO 3-3 参考答案**：缺 assistant(tool_calls) 消息回填 → 工具循环必 400
3. **TODO 4-2 参考答案**：`embeddingService.embed(String)` → 应为 `embed(List.of(...))` 且返回 List 取 [0]
4. **TODO 5-2 参考答案**：`AtomicReference<DayStat> = computeIfAbsent(...)` → 类型不匹配，去掉 AtomicReference

### 必须修正的"虚构 API"（项目里不存在，需先新增）
`JsonUtils`、`generateSimple`、`generateWithSystem`、`removeByDocumentId`、`parseAndSave`、`insertMemory`、`searchMemory`、`getDocId`、`Bm25Tokenizer`（Java）、`Bm25Weight`（Java）、`chunkRepo`

### 必须修正的事实性错误（外部服务）
- 智谱 rerank 模型名：`bge-reranker-v2-m3` → **`rerank` / `rerank-pro`**
- 智谱 rerank 接口：**不是 OpenAI 兼容**
- DeepSeek 模型名：`deepseek-chat` → **`deepseek-v4-flash` / `deepseek-v4-pro`**（2026-07-24 起弃用）
- Milvus BM25："2.4+ 原生支持" → **2.5+ 内置 BM25 Function；2.4 仅稀疏向量需自算**

### 经验值（无官方标准，标注为 🔶，用测试集校准）
0.35 分数阈值、score>0.5 记忆召回阈值、alpha=0.7、maxIterations=5、熔断 5次/60s、反思 +50% 成本

### 待确认项及核实途径
| 项 | 不确定来源 | 核实途径 |
|---|---|---|
| Milvus 2.4 非主键字段 delete 是否 100% 兼容 | Java v2.2.x 旧文档写"只支持 pk in" | 跑 `deleteByDocumentId` 后看 `deleteCount`；或改用 chunkId 主键删除；查 https://milvus.io/docs/delete-entities.md |
| BM25 路线 A 需要的 Milvus 版本 | 官方 bm25-function 文档属 2.5.x 系列 | 查你的 `docker-compose.yml` 中 milvus image tag，确认后升级；看 https://milvus.io/docs/bm25-function.md |
| 中文语义相似度阈值区间 | 无官方标准，因 embedding 模型而异 | 建 50 题测试集看分数分布 |
