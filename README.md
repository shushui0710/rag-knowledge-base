# RAG 智能知识库问答系统

基于 RAG（检索增强生成）的企业级智能知识库问答平台：文档上传解析、分块向量化入库、混合检索与大模型问答，附带 JWT 认证与多用户数据隔离，并已演进为 **Agentic RAG** —— 通过 Function Calling + ReAct 循环、意图路由与多 Agent 编排，让助手能自主决定"检索文档 / 查询统计 / 生成报告"。

核心亮点：

- **混合检索**：Milvus 2.5 内置 BM25 Function，稠密 + 稀疏双路召回，加权融合（alpha=0.7）后经智谱 Rerank 精排（召回 20 → 精排 5），并按最低相似度 0.35 过滤
- **Agentic 能力**：ReAct 循环（≤5 轮）+ 工具调用 + 意图路由（DOCUMENT / STATS / REPORT / HYBRID）+ 多 Agent 编排；反思评审（LLM-as-Judge）带证据评审并限 1 次重写，**只作用于"面向短问答、由 LLM 自由生成"的回答（仅 DOCUMENT 一条分支）**（STATS 的工具直出确定性事实、REPORT 的长结构化产物、HYBRID 的组合拼接都跳过重写）
- **生产化设计**：长期记忆（qa_memory）、逐依赖降级（4 条路径：混合检索→纯稠密 / Rerank→原分 / 查询改写→原句 / 记忆·评审 fail-open）+ LLM 熔断器（5 次/60s，**挂在全站 LLM 唯一出口 ⇒ 全链路覆盖**）、指标观测、评估集回归、**73 用例验收套件（A1–A7 + 评估，真实 HTTP 全链路）**

## 核心功能

| 模块 | 功能 |
|------|------|
| 用户认证 | 注册 / 登录（JWT + BCrypt），`JwtInterceptor` + ThreadLocal 登录态，路由守卫 |
| 文档管理 | 上传（PDF/Word/MD/TXT，支持分类）、解析分块、向量化入库；删除为**级联清理**：Milvus 向量 → MinIO 对象 → MySQL 分块 → 文档行，`@Transactional(rollbackFor=Exception)` 包裹 + 归属校验（越权返回业务码 403） |
| 索引运维 | 混合检索索引重建（`ADMIN` 角色 + 二次确认串 → **异步受理** → 同路径轮询进度）：结构已就绪走原地重灌（**零索引真空期**），旧结构升级走**影子表 + 改名切换**（回放期间旧表继续服务） |
| 角色准入 | `user.role`（`USER` / `ADMIN`）：破坏性运维操作仅 `ADMIN` 可达，注册一律 `USER`，提权只能由运维在库侧执行 |
| 智能问答 | 多会话管理、来源引用、Markdown 渲染、历史记录、会话标题修改 |
| Agentic 问答 | 单 Agent（ReAct + 工具调用）、多 Agent 编排（主管分派 + 证据驱动反思评审） |
| 长期记忆 | qa_memory 独立 collection 存问答对，问答时自动召回相关历史 |
| 检索评估 | `docs/eval/questions.json`（20 题）+ EvalRunnerTest 命中率评测 |
| 指标观测 | 今日问答量、平均耗时、LLM / 工具调用次数 |
| 数据隔离 | 文档、会话、记忆均按用户隔离；检索支持 documentIds 过滤（含降级路径） |
| 接口文档 | Knife4j 在线 API 文档（http://localhost:18080/doc.html） |
| 验收套件 | A1–A6 六个验收域 + 评估回归，共 66 个用例走真实 HTTP（见「测试与验收」） |

## 系统架构

```
                          ┌──────────────────────────────────────────┐
                          │              前端 Vue3 :5173              │
                          │  LoginView / DocumentView / ChatView    │
                          └────────────────┬─────────────────────────┘
                                           │ Axios + JWT(Bearer)
                                           ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                    Spring Boot 后端 :18080 (模块化单体)                    │
│                                                                          │
│  ┌─────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ ┌─────────────────┐  │
│  │  auth   │ │ document │ │   chat   │ │  agent  │ │       rag       │  │
│  │ 认证模块 │ │ 文档模块 │ │ 问答模块 │ │ Agent   │ │ 检索增强/路由/   │  │
│  │         │ │          │ │          │ │ 编排+工具│ │ 改写/重排/记忆  │  │
│  └─────────┘ └────┬─────┘ └────┬─────┘ └────┬────┘ └────────┬────────┘  │
│                   │            │            │               │            │
│  JwtInterceptor ──┴──── UserContext (ThreadLocal 持有当前用户) ──┘        │
└──────────────────────┬──────────────────────┬────────────────────────────┘
                       │                      │
           ┌───────────┼───────────┐          │
           ▼           ▼           ▼          ▼
      ┌────────┐ ┌────────┐ ┌─────────┐ ┌────────────┐
      │ MinIO  │ │ MySQL  │ │ Milvus  │ │DeepSeek API│
      │ 文件存储│ │元数据+ │ │向量检索  │ │ + 智谱API   │
      │ :9000  │ │分块+会话│ │ :19530  │ │(Embedding/ │
      └────────┘ └────────┘ └─────────┘ │ Rerank)    │
                                        └────────────┘
```

### 三条核心链路

**离线入库**：文档上传 → MinIO 存储 → PDFBox/POI 解析 → 滑动窗口分块（512/64）→ 智谱 Embedding 向量化 → Milvus 存储

**在线问答**（`ChatServiceImpl.ask`）：

```
提问 → ① 先把提问落库（失败也留下记录，便于排查与续聊）
     → ② 向量化问题
     → ③ 查当前用户已向量化文档 ID 列表（检索层隔离用）
     → ④ 长期记忆召回（旁路，异常静默返回空）
     → ⑤ LLM 查询改写（失败回退原句）→ ⑥ 混合检索（稠密 + BM25 稀疏，召回 20）
     → ⑦ Rerank 精排（Top 5，API 失败按原分排序）
     → ⑧ minScore 0.35 过滤（过滤后为空 → 兜底文案直接返回，不调 LLM）
     → ⑨ 拼 Prompt（参考片段 + 历史问答记录）→ DeepSeek 生成
     → ⑩ 落库回答 + sources（chunkId/score/100 字预览）→ 检索最高分 ≥0.6 才回存长期记忆
```

> **分数口径**：混合检索的**排序依据**是融合分（0.7×稠密 + 0.3×稀疏），但对外暴露的 `score`
> 对稠密路命中的分块仍是**稠密原分**（仅稀疏路独有的分块才回填融合分）。
> 这样 `minScore` 阈值始终筛的是量纲一致的 COSINE 语义分，代价是 sources 展示分 ≠ 排序分。

**Agentic 问答**（**一条链路、两个角色**：编排层挑活 → ReAct 引擎干活 → 统一收口。**用户侧只有一个入口——对话页「深度思考」开关**；编排层的 `ReportAgent` 复用 ReAct 引擎的**同一个实例**；`AgentController` 只保留一条引擎直连端点 `/api/agent/ask`，定位是调试口，不是产品入口）：

```
★ 产品入口（对话页「深度思考」开关开启时）
POST /api/chat/ask/{sessionId}  {"question":"...","mode":"agent"}
   → ChatServiceImpl.askByAgent：读最近 10 条会话历史
   → 回答 + 依据片段（sources[].type = "evidence"）
   → 按与普通问答**完全相同**的落库路径写入（先存提问 → 存回答），因此历史可回读、指标可跟踪
   → 前端显示「AI助手 · 多 Agent」标签；依据片段无相似度时按语义降级展示（不伪造 score）
   → mode 为空 → 走下方 RAG 主链路（行为与改造前完全一致）；mode 非法 → 静默回退 RAG，不把问答打挂

【编排层 · 挑活】OrchestratorAgent.executeResult(question, history)（主管模式）
   提问 → [RouterService 意图路由 temperature=0.1]
      ├─ DOCUMENT → DocumentAgent：RAG 检索链（查询改写 → 混合检索 → Rerank → LLM 生成）
      │               → 支持多轮：注入最近 6 轮历史（单条截断 200 字）
      ├─ STATS    → StatsAgent：工具直答（query_document_stats + query_document_list）
      │               → 跳过反思评审（确定性事实，重写只会降质）
      ├─ REPORT   → ReportAgent：交给 ReAct 引擎（复用同一个 AgentExecutor 实例，不重写第二套循环）
      │               → LLM 自主调用 generate_report（RAG 检索 → 引言/现状/问题/建议 的 Markdown 报告）
      │               → 跳过反思评审（长结构化产物，重写会把章节推平）
      └─ HYBRID   → StatsAgent + DocumentAgent 组合回答
                    （"【数据概况】\n{统计}\n\n【文档解答】\n{问答}"）
   → 反思评审 CriticService.judge(question, answer, evidence)
      → 不合格则带评审意见 LLM 重写（critic-max-retry=1 硬上限；重写返回空内容或调用失败都保留原答案）

【ReAct 引擎 · 干活】AgentExecutor（仅 REPORT 分支与工具组合不确定的长尾请求进入）
   提问 → 思考 → 调工具 → 观察 → 再思考（≤5 轮）
        → 最终回答 / 超轮降级文案 / 熔断兜底文案
   ※ 熔断判断已不在这一层：它随指标一起收口到全站 LLM 唯一出口 LlmService（见下方「降级策略」）。

※ 附注（非产品入口）：引擎直连端点 POST /api/agent/ask → 直连 AgentExecutor（仅供调试与验收取证；
   不落库，不对外承诺）。产品侧可达 ReAct 能力的唯一入口是对话页「深度思考」——编排层的 REPORT 分支
   复用的就是这个引擎实例。
```


> **注意**：ReAct 引擎只被编排层的 REPORT 分支复用（`ReportAgent` 直接复用 `AgentExecutor`，不另写一套循环），引擎直连端点走的也是它；
> HYBRID 是"子 Agent 组合"而非 ReAct 循环。反思评审只对"面向短问答、由 LLM 自由生成"的回答生效（仅 DOCUMENT），
> 且必须把 `AgentResult.evidence`（工具输出 / 检索片段）交给评委 —— 传空证据会让任何回答都被判"无知识库依据"，
> 触发一次纯 LLM 重写并把准确数字换成模糊复述；STATS（工具直出确定性事实）、REPORT（长结构化产物）与 HYBRID（组合拼接，其中【数据概况】段与 STATS 同源）直接跳过评审。
>
> **为什么要接产品入口**：编排、路由直答、反思重写这类能力必须落在用户已经在用的入口上——
> 不新增第四个页面，而是把 Agent 能力接到对话页（`mode=agent`），复用同样的落库路径与来源展示，
> 既保留 API 形态，又让能力真正产生用户价值。
>
> **同一条思路还收口了两处**：
> ① **报告生成**：`generate_report` 原先只有引擎直连端点够得着
> ⇒ 把 `REPORT` 纳入意图路由，报告能力随链路③ 一起进对话页（顺带让"装配了却选不中"的 `ReportAgent` 从死分支变活分支）；
> ② **熔断器**：原先只覆盖引擎直连那条路（主问答链 `/api/chat/ask` 不设防）
> ⇒ 连同指标一起收口到全站 LLM 唯一出口 `LlmService`，从此与调用方无关地覆盖全站。
> 两处的完整来龙去脉见下方缺陷表 **G-07** / **G-09**。

> **降级策略**：collection 未重建（无 BM25 字段）时混合检索自动降级为纯稠密（**降级仍保留 documentIds 用户过滤**）；Rerank API 失败时降级按原分数排序；查询改写失败用原句；记忆召回/评审异常静默 fail-open；**熔断器收口在全站 LLM 唯一出口 `LlmService`（连续失败 5 次 → 熔断 60 秒、成功清零），因此主问答链、编排链、ReAct 链以及意图路由 / 查询改写 / 反思评审这些子任务调用全部受保护**——熔断期内一个请求都不发（`llmCalls` 零增长），各链路按自身语义降级：问答返回统一兜底文案（且不写入长期记忆）、路由回落 DOCUMENT、改写退回原句、评审放行、ReAct 返回降级提示。


### 链路逐步数据流：每一步对应用户操作的哪一步 + 数据在代码里怎么流

> 上面三条链路已按「做什么 / 为什么 / 代码在哪个类」讲了一遍。这一节回答两个更细的问题：**① 每一步对应用户操作的哪一步；② 这一步的数据在代码里读写到哪一处**。「数据怎么流」一列的方向即读/写方向，落点用 `MySQL 表` / `Milvus collection` / `MinIO bucket` / 外部 API 标注。

**链路① 离线入库**（用户动作：文档管理页「选分类 → 拖拽/点击上传」+ 表格行「向量化」）

| 步 | 用户操作 / 界面反馈（前端 → 端点） | 代码（Controller → 实现方法） | 数据怎么流（读 / 写落点） |
|---|---|---|---|
| 1 | 文档管理页拖拽/点击上传（`DocumentView.vue` 的 `el-upload`，`accept` 只放 pdf/docx/md/txt）→ `POST /api/document/upload`（multipart：`file` + `category`） | `DocumentController.upload` → `DocumentServiceImpl.upload` → `validateFile` | 纯内存校验、不落任何存储：白名单 pdf/docx/md/txt、≤50MB、非空、文件名 ≤200 字符；不通过 ⇒ `BusinessException` ⇒ HTTP 400 |
| 2 | （同一次提交内） | `MinioService.generateObjectName` → `uploadFile` → `ensureBucketExists` | **写 MinIO** `rag-documents`：对象键 `documents/yyyyMMdd/原文件名`（桶不存在则惰性 `makeBucket`） |
| 3 | （同上） | `DocumentServiceImpl.upload` → `documentMapper.insert` | **写 MySQL `document`**：`user_id`（= `UserContext.getUserId()`，源自 JWT）、`title`/`file_name`/`file_type`/`file_size`/`category`/`minio_path`，`chunk_count=0`、`embedding_status=0`；自增 `id` 回填实体 |
| 4 | （同上） | `DocumentParserService.parse` | 读 MinIO 文件流 → 内存全文（PDFBox / POI / 纯文本），**不落库** |
| 5 | （同上） | `DocumentChunkService.chunkAndSave` → `splitText` | **写 MySQL `document_chunk`**：512 窗口 / 64 重叠 / 步长 448，逐块写 `document_id`/`chunk_index`/`content`/`char_count` |
| 6 | 上传成功提示出现，表格多出一行（`chunkCount` 已回写） | `DocumentServiceImpl.upload` → `documentMapper.updateById` | **写 MySQL `document`**：回写 `chunk_count` |
| 7 | 表格行点「向量化」按钮 → `POST /api/document/embed/{id}` | `DocumentController.embed` → `DocumentServiceImpl.embed` | **读 MySQL `document`**：存在性 + **归属校验（非归属 ⇒ 业务码 403）** + 幂等（`embedding_status==1` 直接拒绝） |
| 8 | （同一次请求内） | `DocumentChunkService.listByDocumentId` → `EmbeddingService.embed` | **读 MySQL `document_chunk`**（按 `chunk_index` 升序）→ 调 **智谱 embeddings**（≤64 条/批、`dimensions=2048`；命中进程内缓存——上限 5000——则免调用） |
| 9 | （同上） | `MilvusService.insertVectors` | **写 Milvus `rag_document_chunks`**：行 = `id`（= `document_chunk.id`）/ `document_id` / `content` / `embedding`；`bm25_vector` 由服务端 BM25 Function 自动生成（故必须走 v2 insert） |
| 10 | 表格「向量化状态」标签由「待入库」变「已入库」 | `DocumentServiceImpl.embed` → `documentMapper.updateById` | **写 MySQL `document`**：`embedding_status=1` ⇒ 此后检索链圈定的 `documentIds` 才会包含该文档 |

同页其它写操作（不属于主链路，但用户点得到）：

| 用户操作 | HTTP 端点 | 代码 | 数据怎么流（读写落点） |
|---|---|---|---|
| 点「删除」 | `DELETE /api/document/{id}` | `DocumentServiceImpl.delete`（`@Transactional`） | ① **删 Milvus** `deleteByDocumentId`（`document_id in [id]`）；② **删 MinIO** 对象（失败仅告警）；③ **物理删 MySQL `document_chunk`**；④ **逻辑删 MySQL `document`**（`deleted=1`）。顺序＝先外部后 MySQL，失败可整体回滚 |
| 运维专区点「重建索引」（仅 `ADMIN` 账号可见该区块） | `POST /api/document/rebuild-index`（body 带 `confirm=CONFIRM-REBUILD`） | `DocumentController.rebuildIndex` → `IndexRebuildServiceImpl.submit` | 受理即返回任务快照；后台 `IndexRebuildWorker.runAsync` **读 MySQL `document_chunk`** → 重新向量化 → **写 Milvus**（结构已就绪 ⇒ 原地逐文档重灌；待升级 ⇒ 影子表 `xxx__rebuild` 灌满后改名切换） |
| 页面加载（普通账号也会成功返回） | `GET /api/document/rebuild-index` | `IndexRebuildServiceImpl.status` | **读**当前用户角色 + 内存任务快照；无权者 `allowed=false`、`task=null`（不侧漏运维信息） |
| 分类下拉切换 | 无新端点 | `DocumentView.loadDocuments`（前端本地过滤） | `GET /api/document/list` 一次性取回当前用户文档，前端按 `category` 过滤 |

**链路② 在线问答**（用户动作：对话页输入框输入 → 回车/点发送 → 看回答并展开「参考来源」；下表 12 步是同一次点击内部的分步）

| 步 | 用户操作 / 界面反馈（前端 → 端点） | 代码（Controller → 实现方法） | 数据怎么流（读 / 写落点） |
|---|---|---|---|
| 1 | 输入框输入问题 → 回车/点「发送」（`ChatView.vue` 的 `sendQuestion()` → `api/chat.js` 的 `askQuestion`）→ `POST /api/chat/ask/{sessionId}`，body `{"question":"…"}`（**不开**「深度思考」开关） | `ChatController.ask` → `ChatServiceImpl.ask`（`mode` 为空 ⇒ `askByRag`） | **写 MySQL `chat_message`**：`role=user`、`content=原问题`、`sources=null`（数据优先：后续检索/LLM 失败也留痕） |
| 2 | 气泡显示 loading 三点动画（用户仍在等） | `RetrievalChain.retrieve` → `EmbeddingService.embed`（`embedSingle` 带缓存） | 调 **智谱 embeddings**：问题 → 2048 维向量；命中缓存则不发请求 |
| 3 | （同上） | `RetrievalChain.scopeOf(userId)` → `DocumentMapper.selectList` | **读 MySQL `document`**：`user_id=? AND embedding_status=1` ⇒ 得到 `documentIds`（检索层隔离依据；空列表 ⇒ 直接空召回，连一次注定为空的 RPC 都不发） |
| 4 | （同上） | `MemoryService.recall` → `MilvusMemoryStore.searchMemory(vec, 3, userId)` | **读 Milvus `qa_memory`**（`user_id` 过滤 + `score＞0.5` 取 Top3）；异常 ⇒ 返回空列表（fail-open 等同无记忆） |
| 5 | （同上） | `QueryRewriterService.rewrite` → `LlmService.chatWithSystem(temp 0.2)` | 调 **DeepSeek**：口语问题 → 2-3 个检索短语；失败/熔断 ⇒ 返回原句 |
| 6 | （同上） | `MilvusService.hybridSearch(改写词, 向量, 20, documentIds)` | **读 Milvus `rag_document_chunks`**：稠密路（v1 `search` + `expr document_id in [...]`）+ 稀疏路（v2 `EmbeddedText` + BM25），按 `chunkId` 融合 `0.7×稠密 + 0.3×稀疏`，降序取 20 |
| 7 | （同上） | `RerankService.rerank(question, hits, 5)` | 调 **智谱 rerank**：20 → 5；失败 ⇒ 按原分排序降级 |
| 8 | （同上） | `RetrievalChain.retrieve` 内 `filter(score ≥ minScore=0.35)` | 内存过滤（不落存储）；过滤后为空 ⇒ `Outcome.empty()` ⇒ 下一步走兜底文案，**完全不调 LLM** |
| 9 | （同上） | `ChatServiceImpl.askByRag` 用 `promptTemplate` 的 `{context}` / `{question}` 占位符拼 Prompt | 拼接【参考N】片段（+ 有记忆时【历史问答记录】块）；**改写词只用于检索、Prompt 仍用原问题** |
| 10 | 三点动画结束，回答气泡出现 | `LlmService.chat(prompt)` | 调 **DeepSeek** `deepseek-v4-flash`（temp 0.7、`max_tokens=8192`）；熔断 ⇒ 返回统一兜底文案且 `degraded=true`（不进记忆） |
| 11 | 回答下方「参考来源」可展开，来源项带相似度标签 | `ChatServiceImpl.askByRag` → `ChatSessionServiceImpl.appendMessage` | **写 MySQL `chat_message`**：`role=assistant`、`content=answer`、`sources=[{chunkId, score, content 前 100 字}]` |
| 12 | 界面无变化（后台写记忆） | `MemoryService.saveExchange` → `MilvusMemoryStore.insertMemory` | **写 Milvus `qa_memory`**：仅当 `topScore≥0.6` 且未降级；文本按 UTF-8 字节截到 2048，**向量与入库用同一份文本** |

**链路③ Agentic**（用户动作：对话页打开「深度思考」开关再提问；下表 5 步是编排层，第 4 步才进 ReAct 引擎）

| 步 | 用户操作 / 界面反馈（前端 → 端点） | 代码（Controller → 实现方法） | 数据怎么流（读 / 写落点） |
|---|---|---|---|
| 1 | 输入框工具栏打开「深度思考」开关（`deepThink=true`）→ 输入问题 → 发送：`POST /api/chat/ask/{sessionId}`，body `{"question":"…","mode":"agent"}` | `ChatController.ask` → `ChatServiceImpl.ask`（`mode=agent` ⇒ `askByAgent`） | 先 **读 MySQL `chat_message`**（`recentMessages(sessionId, 10)` 取最近 10 条）→ 再 **写 MySQL `chat_message`**（`role=user`）。**顺序不能反**：先落库会把本轮问题也读进历史，Prompt 里问题出现两次 |
| 2 | 回答回来后气泡上方显示「AI助手 · 多 Agent」标签 | `OrchestratorAgent.executeResult` → `RouterService.route` → `LlmService.chatWithSystem(temp 0.1)` | 调 **DeepSeek** 做四分类 DOCUMENT / STATS / REPORT / HYBRID；失败/熔断 ⇒ 回落 DOCUMENT（宁可多检索，不让问题没人答） |
| 3a | 问「有多少文档 / 列出文档」类问题 ⇒ 数字直答 | `StatsAgent.execute` → `ToolRegistry.execute("query_document_stats" / "query_document_list")` → `DocumentService.stats` / `listByCategory` | **读 MySQL `document` + `document_chunk`**（按 `user_id` 隔离聚合）；工具原文即 `evidence`，**不经过 LLM** |
| 3b | 问文档内容类问题 ⇒ 走检索（与链路② 同一条链） | `DocumentAgent.execute` → `RetrievalChain.retrieve`（同链路② 步 2~8）→ `LlmService.chat` | 读 Milvus / MySQL（检索）+ 调 DeepSeek；`topScore≥0.6` ⇒ **写 Milvus `qa_memory`**（与链路② 共用同一门槛） |
| 3c | 说「生成一份关于 XX 的报告」⇒ 进 ReAct 引擎 | `ReportAgent.execute` → `AgentExecutor.executeResult`（构造「必须调用 `generate_report`」的任务指令） | 引擎内调 `generate_report` → `GenerateReportTool.execute` 内部**再走一次 `RetrievalChain.retrieve`** → 调 DeepSeek 生成 Markdown 报告正文（`deliverable=true`） |
| 3d | 既问数量又问内容 ⇒ 两段式组合回答 | `OrchestratorAgent`（HYBRID 分支）：先 `StatsAgent` 再 `DocumentAgent` | 拼接 `【数据概况】` + 统计文本 + `【文档解答】` + 问答文本；**跳过反思**（两段天然「看似矛盾」，重写必然抹平结构） |
| 4 | （仅 REPORT 分支与工具组合不确定的长尾进入；界面仍是 loading） | `AgentExecutor.executeResult`：≤5 轮 `LlmService.chatWithTools(messages, tools)`（temp 0.3、`tool_choice=auto`）；TOOL_CALL ⇒ `ToolRegistry.execute(name, args)` → 结果作 `role=tool` 消息回填 | 每轮 **调 DeepSeek**（`llmCalls` 在 `LlmService` 唯一出口计数、`toolCalls` 在 `ToolRegistry.execute` 计数）；工具输出截 500 字作 `evidence`，**产物型工具输出（`deliverable=true` 且 ≥200 字）原样作答、不截断** |
| 5 | 回答渲染 + 「参考来源」展开显示「依据片段」（无相似度标签） | `OrchestratorAgent`（**仅 DOCUMENT 分支**）`CriticService.judge(question, answer, evidence)` → 不合格 `LlmService.chatWithSystem(temp 0.4)` 重写（硬上限 1）→ `ChatServiceImpl.askByAgent` → `appendMessage` | 调 DeepSeek 评审/重写；**写 MySQL `chat_message`**：`role=assistant`、`content=answer`、`sources=[{content, type:"evidence"}]`（无相似度，不伪造 `score`）；刷新页面历史可回读；指标可从 `GET /api/metrics/today` 读 `AgentMetrics` 计数 |

**横切：认证与数据隔离**（用户动作：登录 + 之后每一次带 token 的请求）

| 步 | 用户操作 / 界面反馈（前端 → 端点） | 代码（Controller → 实现方法） | 数据怎么流（读 / 写落点） |
|---|---|---|---|
| 1 | 登录页输入用户名 / 密码，点登录 | `LoginView.vue` → `POST /api/auth/login` → `AuthController` → `UserServiceImpl` | **读 MySQL `user`**（按 `username`）→ BCrypt 校验 `password`（密文比对，内嵌随机盐）；通过后 `JwtUtil` 签发 token（HS256，24h） |
| 2 | 登录成功后 token 存 localStorage；此后每个请求由 `api/index.js` 请求拦截器自动注入 `Authorization: Bearer` 头（值为登录拿到的 token） | （前端职责，无后端代码） | 前端本地存储；后端每次请求都独立验签（无状态认证，不查库） |
| 3 | 任意需登录的接口调用 | `WebMvcConfig` 注册的 `JwtInterceptor.preHandle` | 验签 → `jwtUtil.getUserIdFromToken` → **写 `UserContext`（ThreadLocal）**；排除路径精确到 `/api/auth/login`、`/api/auth/register` |
| 4 | （用户无感） | 各 Service 内 `UserContext.getUserId()`；会话/文档按 id 的入口统一过 `requireOwner()` | 三层隔离读：**MySQL `eq(user_id)`** / **Milvus `expr document_id in [...]`** / **Milvus `qa_memory` `user_id==`**；越权 ⇒ 抛业务码 403（HTTP 仍 400） |
| 5 | 请求结束（响应已返回） | `JwtInterceptor.afterCompletion` | **清 `UserContext`（`remove()`）**——Tomcat 线程池复用线程，不清会被下一个请求读到旧 `userId` 造成串号 |

## 技术栈

| 层 | 技术 | 版本 | 说明 |
|----|------|------|------|
| 后端框架 | Spring Boot | 3.2.5 | Java 17，模块化单体架构 |
| ORM | MyBatis-Plus | 3.5.5 | 零代码 CRUD + 逻辑删除 + 自动填充 |
| 认证 | JJWT + spring-security-crypto | 0.12.5 / 6.3.0 | JWT 签发解析 + BCrypt 密码加密 |
| 数据库 | MySQL | 8.0 | Docker 容器，端口 3307→3306 |
| 向量库 | Milvus | 2.5.16 | Docker 容器，端口 19530；内置 BM25 Function |
| Milvus SDK | milvus-sdk-java | 2.5.14 | v1 API（MilvusServiceClient）；需显式依赖 fastjson |
| JSON | fastjson | 1.2.83 | 显式声明（原由 SDK 2.4.1 传递） |
| 文件存储 | MinIO | 2023.03 | Docker 容器，API 9000 / 控制台 9002 |
| 文档解析 | Apache PDFBox + POI | 3.0.1 / 5.2.5 | PDF / Word / MD / TXT |
| Embedding | 智谱 API（embedding-3） | — | 2048 维向量 |
| Rerank | 智谱 API | — | 精排（召回 20 → 精排 5） |
| 大模型 | DeepSeek API（deepseek-v4-flash） | — | OpenAI 兼容格式 |
| 前端框架 | Vue3 + Element Plus | 3.4 / 2.7 | Vite 构建，Composition API |
| 前端状态 | Pinia | 2.1 | 替代 Vuex，更轻量 |
| 前端路由 | Vue Router | 4.3 | 含登录路由守卫 |
| 接口文档 | Knife4j | 4.4.0 | http://localhost:18080/doc.html |
| 部署 | Docker Compose | — | 一键编排 MySQL + MinIO + etcd + Milvus |

## 快速开始

### 环境要求

| 软件 | 最低版本 | 验证命令 |
|------|----------|----------|
| JDK | 17 | `java -version` |
| Maven | 3.8+ | `mvn.cmd -version`（Git Bash 中用 `mvn.cmd`） |
| Node.js | 18+ | `node -v` |
| Docker Desktop | 最新版 | `docker --version` |

> **Windows 用户**：Git Bash 中 Maven 命令需用 `mvn.cmd` 而非 `mvn`，否则路径格式不兼容。

### 第 1 步：配置环境变量

```bash
git clone <仓库地址>
cd rag-knowledge-base
cp .env.example .env
```

编辑 `.env`，填入以下关键项（完整字段见 `.env.example`）：

```env
MYSQL_ROOT_PASSWORD=rag123456
MYSQL_DATABASE=rag_kb
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin
DEEPSEEK_API_KEY=your-deepseek-api-key     # https://platform.deepseek.com
ZHIPU_API_KEY=your-zhipu-api-key           # https://open.bigmodel.cn
JWT_SECRET=your-random-secret              # 生产环境务必修改
```

### 第 2 步：启动基础设施

```bash
docker-compose up -d
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

预期 5 个容器均为 healthy：

```
NAMES                STATUS              PORTS
rag-mysql            Up (healthy)        0.0.0.0:3307->3306/tcp
rag-minio            Up (healthy)        0.0.0.0:9000,9002->9000,9002/tcp
rag-milvus           Up (healthy)        0.0.0.0:19530,9091->19530,9091/tcp
rag-milvus-etcd      Up (healthy)
rag-milvus-minio     Up (healthy)        0.0.0.0:9001->9001/tcp
```

> MySQL 首次启动会自动执行 `docker/mysql/init/init.sql` 建表。

### 第 3 步：启动后端

> ⚠️ Spring 不会自动读取 `.env` 文件，需先加载环境变量（实测踩坑）：

```bash
# Git Bash
set -a && source .env && set +a
cd backend
mvn.cmd spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

IDE（IntelliJ）方式：Run Configuration → Environment variables 填入 `ZHIPU_API_KEY=...;DEEPSEEK_API_KEY=...;JWT_SECRET=...`。不加载的话 `embedding.zhipu.api-key` 会用默认占位值，向量化报 401。

启动成功后控制台显示 `Started RagKnowledgeBaseApplication`，接口文档：http://localhost:18080/doc.html

### 第 4 步：启动前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 http://localhost:5173 （Vite 已将 `/api` 代理到 `localhost:18080`），注册账号后即可使用。

## 使用指南

### Web 界面

1. **注册登录**：首次访问跳转登录页，注册并登录；
2. **上传文档**：文档管理页上传 PDF/Word/MD/TXT（可选分类），上传后点击"向量化"入库；
3. **智能问答**：对话页创建会话提问，回答附带来源引用，支持 Markdown 渲染。

### API 调用示例

所有接口（除注册/登录）需在请求头携带 `Authorization: Bearer <token>`。

```bash
# 1. 注册并登录，获取 token
curl -X POST http://localhost:18080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123"}'

curl -X POST http://localhost:18080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123"}'
# → {"data":{"user":{...},"token":"eyJhbGci..."}}

TOKEN=eyJhbGci...

# 2. 上传文档（可选 category）并触发向量化
curl -X POST http://localhost:18080/api/document/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./产品手册.pdf" -F "category=技术"
curl -X POST http://localhost:18080/api/document/embed/1 \
  -H "Authorization: Bearer $TOKEN"

# 3. 创建会话并提问（RAG 问答）
curl -X POST http://localhost:18080/api/chat/session -H "Authorization: Bearer $TOKEN"
curl -X POST http://localhost:18080/api/chat/ask/1 \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"产品的保修政策是什么？"}'

# 4. Agentic 问答（对话页「深度思考」开关对应的接口形态）
#    加 "mode":"agent" 即走多 Agent 编排；不传 mode 则是普通 RAG 问答（行为与改造前一致）
curl -X POST http://localhost:18080/api/chat/ask/1 \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"目前知识库里有哪些分类的文档？各有多少篇？","mode":"agent"}'

# 4b. 引擎直连形态（不走对话页、不落库；仅供联调与验收取证）
curl -X POST http://localhost:18080/api/agent/ask \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"目前知识库里有哪些分类的文档？各有多少篇？"}'
# 5. 查看今日指标
curl http://localhost:18080/api/metrics/today -H "Authorization: Bearer $TOKEN"
```

### 检索效果评估

评估集位于 `docs/eval/questions.json`（20 题），通过 Spring Boot 测试运行（需后端环境在线）：

```bash
cd backend
mvn.cmd test -Dtest=EvalRunnerTest
```

## 测试与验收

### 运行验收套件

```bash
cd backend
mvn.cmd test -Dtest=A1_AuthAndContractAcceptanceTest,A2_IngestionAcceptanceTest,A3_RetrievalAcceptanceTest,A4_ChatFlowAcceptanceTest,A5*AcceptanceTest,A6_KnownGapAcceptanceTest,A7_ResilienceAcceptanceTest,EvalRunnerTest
```

**前置条件**：5 个容器全部 healthy、`.env` 密钥与运行中的容器一致、后端可访问 MySQL/MinIO/Milvus。

**两个必须的环境开关**（否则会得到看似"代码 bug"的失败）：

| 开关 | 原因 |
|------|------|
| `-Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8` | 中文 Windows 上 JDK 17 默认 GBK，中文经 Milvus BM25 分词会触发 tantivy Rust panic → 容器 SIGABRT（已写入 surefire `argLine`） |
| `-Djava.net.useSystemProxies=false` | 本机若装 Clash 等代理，JVM 会走代理导致 `localhost` 访问失败 / 调用 DeepSeek 报 PKIX 证书链错误 |

验收套件第一条输出即**环境指纹**（java.version / file.encoding / useSystemProxies / 实际选定的代理路由），用于把环境问题与代码问题分离。

### 覆盖范围与通过标准

| 域 | 用例 | 覆盖内容 | 通过标准（P0） |
|----|------|----------|----------------|
| A1 认证与统一契约 | 13 | 注册/登录/重名/密码不回传/统一失败文案、无 token 与篡改 token 拒绝、`Result` 结构、请求体契约（对象 vs 裸 JSON 串）、空问题校验 | 正向返回 200；负向精确返回 **HTTP 400**（非 500）；密码字段恒为 null |
| A2 离线入库 | 15 | 格式白名单/空文件/无扩展名/未登录上传拒绝、上传落库字段、分块算法边界、向量化入库可召回、幂等防重、删除幂等、列表隔离、**索引重建运维闸门（非 `ADMIN` 提交一律 403 / 提权后放行，A2-13）**、**级联删除三处存储且删除后不可再召回（A2-14）**、**越权删除他人文档 403（A2-15）** | 非法输入 400；`chunkCount/embeddingStatus` 与实际一致；向量化后可被检索命中 |
| A3 检索链路 | 8 | 稠密 TopK/降序/content、BM25 稀疏路词面命中、融合排序差异、`documentIds` 隔离、空集合短路、content 完整性、连续 20 次中文检索稳定性、**降级仍保留隔离** | 分数降序、content 非空；跨用户内容不可见（含降级路径）；Milvus `/healthz` 保持健康 |
| A4 在线问答全链路 | 11 | 端到端问答+来源引用、sources 结构、空召回兜底（不调 LLM）、历史顺序、会话级联删除、长期记忆闭环（隔离+门槛）、**长期记忆健壮性（主键不跨重启冲突 + 超长内容不静默丢失）（A4-09）**、**越权读他人会话历史 403（A4-10）**、**越权删除他人会话 403（A4-11）** | 返回 200 且回答非空；sources 含 chunkId/score/预览；兜底路径不产生 LLM 调用；越权访问返回业务码 403 且错误体不侧漏 |
| A5 Agent 链路与熔断 | 15 | （按**变更原因**拆为 A5a~A5f 六个类：熔断单元 2 / ReAct 2 / 编排 4 / 指标 1 / 装配 3 / 对话集成 3；共用夹具 `A5Support`）熔断状态机、**熔断收口到唯一出口（挂点结构 + 三出口守卫）（A5-02）**、ReAct 单 Agent（真实工具调用）、编排 STATS 直答、空问题校验、指标联动、工具注册表、**Agent 证据契约**、**对话页 agent 模式落库回读（A5-10）**、**非法 mode 回退 RAG（A5-11）**、**熔断端到端覆盖三条链路（A5-12）**、**REPORT 分支可达并落库（A5-13）**、**四类路由逐一可达 + 编排与引擎直连端点同一 ReAct 引擎实例（A5-14）**、**HYBRID 组合回答保留【数据概况】/【文档解答】结构（A5-15）** | ReAct 返回非空回答；STATS 回答必须含真实文档数且保留工具计量表述；`AgentResult.evidence` 非空；熔断打开时三条链路均 HTTP 200 + 统一兜底文案且 `llmCalls` 零增长；路由可识别 REPORT 且报告带依据产出 |
| A6 已知缺口固化 | 4 | 重解析无 HTTP 入口、鉴权返回 400 而非 401、标题引号入库、缓存配置未接线（原「会话越权读/删」与「删除不级联」已于 09-20 修复并移出，取证改挂 A4-10/A4-11、A2-14/A2-15） | **断言"当前真实行为"**：修好即失败，强制同步文档（不计入 P0 门槛） |
| A7 鲁棒性与并发 | 6 | 并发射程（4 用户真并发上传+向量化+检索，互不串号）、事务射程（删除中途失败 ⇒ MySQL 侧整体回滚，不留半删态）、级联正向射程（删后三处终态一致 + 同内容重传主键不冲突）、异步射程（闸门拒绝零残留 + 陈旧任务号不覆盖快照 + 专用线程池）、权限矩阵射程（14 个受保护端点无 token 全拒 + 越权写 6 格全 403 零副作用）、超长畸形射程（超长文件名/标题前置校验 400；emoji+零宽+控制字符内容完整入库） | 并发互不污染；异常路径不留半成品数据；越权一律 403 且零副作用；非法/超长输入 **400 而非 500** |
| 评估回归 | 1 | 20 题评估集 Top5 命中率（稠密 vs 混合） | 命中率可复现输出（用于趋势对比，不设硬门槛） |

### 当前结果（2026-09-20）

```
A1  13/13    A2  15/15    A3   8/8    A4  11/11
A5  15/15    A6   4/4     A7   6/6    Eval 1/1
                                        → Tests run: 73, Failures: 0, Errors: 0

> A5 的 15 条由六个类承载：A5a 2 / A5b 2 / A5c 4 / A5d 1 / A5e 3 / A5f 3（原 816 行单文件已于第八轮按变更原因拆分）。
```

> 09-17 重写时当轮为 **60 条**，09-18「C+A 收口」后为 **63 条**（A5 11→13→14）；09-20 补 **A2-13**（索引重建运维闸门）后为 **64 条**；09-20 二轮补 **A4-09**（记忆健壮性）+ **A5-15**（HYBRID 结构保全）后为 **66 条**；09-20 **第七轮**「结构治理」把原 A6 缺口①②③（会话越权读/删、删除不级联）改正向回归（A4-10/A4-11、A2-14/A2-15）后为 **67 条**；09-20 **第八轮**「测试结构治理」新增 **A7 域**（补六类空缺射程：并发 / 事务回滚 / 级联正向 / 异步失败 / 权限矩阵 / 超长畸形）并把 A5 按变更原因拆为 A5a~A5f 后为 **73 条**（验收域 6 → **7**）。引用旧数字须带当轮限定。

关键实测数据：

| 指标 | 实测值 | 来源 |
|------|--------|------|
| 端到端问答耗时（含改写+混合检索+精排+LLM） | 3.13s / 4.78s / 3.58s，均值 **3.83s** | A4-08 |
| 空召回兜底耗时（未调用 LLM） | **1.55s** | A4-03 |
| 单 Agent ReAct 问答（真实工具调用） | **1.93s**（随 LLM 波动 1.9–5.0s） | A5-03 |
| 多 Agent 编排 STATS 分支（工具直答） | **1.18s** | A5-05 |
| 稠密检索首条相似度 | 0.4114 | A3-01 |
| 融合排序差异 | dense `[0.484844, 0.478224]` → hybrid `[0.478224, 0.484844]` | A3-03 |
| 长期记忆重排最高分 / 跨用户召回 | 1.0000 / 本用户 1 条、他人 0 条 | A4-07 |
| 评估集 Top5 命中率（20 题 / 2 文档 33 分块） | 稠密 **100.0%** / 混合 **100.0%** | EV-01 |
| 连续 20 次中文混合检索 | 无异常，Milvus `/healthz` 健康 | A3-07 |

### 测试报告与证据产物

除 JUnit 验收套件外，还跑了一轮**面向全量功能**的黑盒测试（脚本免 JVM，直接打真实 HTTP），三层证据互相印证：

| 层 | 规模 | 产物 |
|----|------|------|
| 后端集成（JUnit） | 7 域 73 用例，`Tests run: 73, Failures: 0` | 本文件「覆盖范围与通过标准」 + 下方「第四轮」缺陷复盘 |
| HTTP 全接口黑盒 | 51 用例（A1–A6 实测 + 补充 S-01…S-08 + **09-20 补 D-05 报告分支**），51/51 PASS；**射程分档**（第八轮新增）：唯一射程 **3** / 可交叉验证 48 / 未登记 0 | `_probe/api_full_suite.mjs` · `api_supplement.mjs` · `api_suite_result.json` |
| UI 全流程截图 | 28 张（23 条前端交互 + 5 张接口证据页），全程无 5xx | `screenshots/全量功能测试-2026-09-17/` |

> 说明：上表「产物」中的 `_probe/` 脚本、`screenshots/` 截图目录与汇总报告 `RAG项目全量功能测试报告-*.html`，均位于**仓库同级的工作区目录**（`../`），因体积较大**未随本仓库提交**；本仓库内保留的是可复跑的验收套件 `backend/src/test/java/com/liushuwen/rag/acceptance/`，按下方 runbook 可重新生成全部证据。

> 三层之外还有一道**交叉核对**（端点清单 × 前端路由 × 前端请求封装三者互查），
> 它不产出用例，但正是它发现了「Agent 模块实现了却没接进产品」这一问题（详见下方缺陷 G-07）。
> 用例只能证明「被测对象自身正确」，证明不了「被测对象被产品用到」——这两件事需要不同的检查手段。

**汇总报告**：`RAG项目全量功能测试报告-2026-09-17.html`（自包含单文件，含用例清单、实测明细、截图墙、缺陷复盘）。

### 已知缺口（A6 用例固化，修好即测试失败）

| # | 缺口 | 影响 |
|---|------|------|
| ① | ~~`getHistory` 只按 sessionId 过滤，不校验会话归属~~ **已修（09-20）** | 原：任一登录用户可用自己的 token 读取他人会话。现：所有按 id 的读写统一过 `ChatSessionService.requireOwner()`，越权返回**业务码 403**（HTTP 仍 400）；取证移到 **A4-10** |
| ② | ~~`deleteSession` 同样不校验归属~~ **已修（09-20）** | 原：可删除他人会话。现：同样过 `requireOwner()` ⇒ 403；取证移到 **A4-11** |
| ③ | ~~`delete(id)` 只做 document 行逻辑删除，未清理 document_chunk / Milvus 向量 / MinIO 对象~~ **已修（09-20）** | 原：数据残留、向量仍可按 document_id 召回。现：**级联清理**（Milvus 向量 → MinIO 对象 → MySQL 分块 → 文档行）+ `@Transactional` + 归属校验 403；取证移到 **A2-14**（级联 + 不再可召回）/ **A2-15**（越权删除 403） |
| ④ | `reparseDocument`（增量重解析）已实现但 **Controller 未暴露** | HTTP 层不可达 |
| ⑤ | 鉴权失败返回 **HTTP 400**，前端仅在 401 时登出跳转 | 前端 401 分支为死代码，token 过期不会自动跳登录页 |
| ⑥ | 会话标题：前端发 JSON 字符串、后端收裸 String | **引号被一并入库**（如 `"验收标题"`） |
| ⑦ | `rag.retrieval.embed-cache-limit` 已配置但 `EmbeddingService` 未注入 | 改 yml 不生效，上限实为硬编码常量 |

> 上述 7 项里 ①②③ 已于 **09-20 第七轮 · 结构治理**修复并**移出** A6（改正向回归，取证挂到 A4-10/A4-11、A2-14/A2-15）；余下 ④⑤⑥⑦ 仍写进 `A6_KnownGapAcceptanceTest`（现 **4 条**，编号**刻意不重排**——它是旧报告的追溯锚点）。A6 是"文档准确性"的可回归护栏：**断言「缺陷当前成立」，修好即失败，强制同步文档**。

### 第一轮（验收套件重写）修复的 6 处缺陷

| 缺陷 | 根因 | 修复 |
|------|------|------|
| 新用户未上传文档即提问返回 **500** | `ChatServiceImpl` 对 Rerank 返回的不可变 `List.of()` 调 `removeIf` → `UnsupportedOperationException` | 改 `stream().filter().collect(toList())` |
| 裸 JSON 串发 `/api/chat/ask` 返回 **500** | `HttpMessageNotReadableException` 落进 catch-all | 新增专用 handler 返回 400 |
| 编排接口可能返回 **HTTP 200 + 空回答** | Critic 重写返回空内容被直接采用 | 空重写保留原回答 |
| 编排 STATS 回答被"重写降质" | 反思评审固定传空证据 → 必判"无依据" → 纯 LLM 重写丢掉准确数字 | 引入 `AgentResult.evidence` 携证据；STATS 工具直答跳过评审 |
| 混合检索降级时**丢失用户过滤** | catch 分支调用不带 `documentIds` 的 `search()` | 降级统一走 `degradeToDense(...)` 保留隔离 |
| 文档统计自相矛盾（有内容分块的文档数 > 文档总数） | `QueryDocumentStatsTool` 用 `inSql` 手写原生子查询，MyBatis-Plus 逻辑删除**只改写框架生成的 SQL** → 已删文档的残留分块被计入 | 子查询补 `deleted = 0 and user_id = ...` |

### 第二轮（全量功能黑盒测试 + 交付前交叉核对）修复的 9 处缺陷（G-01 ~ G-09）

G-01 ~ G-06 都是在"用例全绿"的前提下**靠跨层对照才暴露**的缺陷：断言只校验了业务码/回答内容，没有校验 HTTP 状态码与语义正确性。
G-07 更进一步——它不是"行为不对"，而是"功能正确却没接上产品"；G-08 / G-09 则是核查 G-07 时被指标接口反证出来的埋点口径问题（详见本节末尾）。

| # | 严重度 | 缺陷 | 根因 | 修复 |
|---|--------|------|------|------|
| **G-01** | 中高 | 参数校验失败返回 **HTTP 200**，业务异常返回 HTTP 400 —— 同一类客户端错误两种 HTTP 表现 | 空问题校验写成 `return Result.error(400, ...)`（对象正常返回 → 200），而 Service 抛 `BusinessException` 走 `@ResponseStatus(BAD_REQUEST)` → 真 400 | 三个端点的内联分支统一改 `throw new BusinessException(...)`；并给 A1-12 补「HTTP 状态码必须为 400」断言防回退 |
| **G-02** | 中 | Agent 统计把「分块**行数**」当「**文档**数」上报，造出 `withChunk > total` | `selectCount(...isNotNull(content))` 统计的是 document_chunk 行数，一个文档会切成多块 → 数字必然 ≥ 文档总数 | 改为按 `document_id` 去重计数；A5-05 种子文档改 40 段长文使该场景真正被覆盖 |
| **G-03** | 低 | 前后端文件白名单不一致：前端 `accept` 放行 `.doc`，后端白名单不含 `.doc` | 前端 accept 写 `.pdf,.doc,.docx,.md,.txt`，后端 `ALLOWED_FILE_TYPES` 只有 pdf/docx/md/txt | 前端 accept 去掉 `.doc`，文案由「支持 PDF / Word」改为「支持 PDF / DOCX / Markdown / TXT」 |
| **G-04** | 中 | 超过 50MB 的上传返回 **HTTP 500**「系统内部错误」，把客户端错误报成服务端故障 | `MaxUploadSizeExceededException` 落进 catch-all | `GlobalExceptionHandler` 新增 `MaxUploadSizeExceededException` → **413**、`MultipartException` → **400** |
| **G-05** | 中 | 不存在的路径返回 **HTTP 500**，把 404 误报成服务端故障 | `@ExceptionHandler(Exception.class)` catch-all 吞掉了 Spring 的 `NoResourceFoundException`（Knife4j 请求 `/favicon.ico` 即触发） | 新增 `NoResourceFoundException` / `NoHandlerFoundException` handler → **404** |
| **G-06** | 中 | 超限上传时服务端**直接掐断连接**，客户端只拿到不透明网络错误（`Error writing request body to server`），拿不到 413 | Tomcat `max-swallow-size` 默认 2MB < 请求体 → 连接在返回 413 之前就被掐断 | `application.yml` 设 `server.tomcat.max-swallow-size: 64MB`（限量而非无限，避免放任超大 body） |
| **G-07** | **高（产品级）** | **Agent 模块"实现了但没被产品用上"**：问答主链路 `ChatServiceImpl.ask` 是另一套内联 RAG 实现，编排能力当时只挂在两个没有前端入口的后端接口上，前端三个页面里 `agent` 出现 **0 次** | 第 8 周交付的 Agent 体系与主问答链路是**两条并行实现**，从未接线；`DocumentAgent` 的 `history` 形参也一直没被使用 | 不新增页面，把编排接进用户已在用的问答入口：`AskRequest` 加 `mode` 字段 → `ChatServiceImpl.askByAgent` 调 `OrchestratorAgent.executeResult()` → 走同样落库路径；`DocumentAgent` 让 history 真正生效（6 轮 / 200 字截断）；前端对话页加「深度思考」开关 |

> **共性（面试可讲的判断力）**：G-01 / G-04 / G-05 都是**"HTTP 状态码语义"**层面的问题——返回体看起来对（业务码正确），但 HTTP 层骗过了网关/监控/第三方集成；G-02 是**"修 bug 修错病因"**（把 DISTINCT 缺失误判为逻辑删除穿透）；G-03 / G-06 是**"层与层之间契约不对齐"**（前端 offer 了什么 vs 后端接受什么，容器 swallow 上限 vs 应用声明的上限）。四类都能在"用例全绿"下存活，说明**只测业务码不测 HTTP 语义**是有盲区的。
>
> **G-07 与前六项不同，必须单独说清**：前六项是「某个功能行为不对，一条用例就能复现」；G-07 是「功能本身是对的，但没有任何产品路径会调用它」。
> 它是靠**交叉核对**（端点清单 × 前端路由 × 前端请求封装三者互查）发现的，不是任何一次测试执行暴露的——
> 因为三端证据天然共享同一个盲区：API 用例直接打端点、后端用例直接调服务、截图脚本按页面点击，
> 而页面上**根本没有 Agent 入口**。运行时旁证也很直白：以 `GET /api/metrics/today` 为证人，
> 连调 3 次 `/api/chat/ask` 指标毫无变化（产品主链路不记指标），调 1 次 `/api/agent/ask` 指标立即变化。
> **结论**：「用例全绿」只能证明被测对象自身正确，证明不了它被产品使用——这是本次最值得记住的一条方法论。

#### G-08 / G-09：指标埋点口径修正（同一轮交叉核对中一并发现并修掉）

> 这两项是在核查「Agent 是否真的被用上」时，被指标接口**自己反证**出来的：连调 3 次 `/api/chat/ask`（用户真实链路）指标毫无变化。
> 我一度按"观测层问题、不影响回答正确性"把它们登记为**刻意不修**，随后推翻了这个判断——
> **G-07 能被发现，靠的正是这个接口"诚实地"暴露了主链路没被埋点**。把一个既漏记、又双计的指标留着，
> 却继续拿它的读数当对外口径，等于把唯一能发现"模块空转"的报警器自己拆掉。

| # | 问题 | 现象与证据 |
|---|------|-----------|
| G-08 | **指标双计**：`AgentExecutor` 循环内已调 `recordLlmCall()`，收尾又调 `recordQuery(cost, iterations, toolCount)`，而后者内部再次 `addAndGet(...)` | 实测 1 次 `POST /api/agent/ask` 指标 **+4 LLM / +2 工具**，真实值是 2 次 LLM / 1 次工具 —— 恰好是 `iterations×2`、`toolCount×2`。盲区成因：A5-06 只断言 `llmCalls > 0` 与 `queryCount` 增长，**不校验数值**——"大于零"这种弱断言能放过"翻倍"这种量级错误 |
| G-09 | **指标覆盖缺口**：`AgentMetrics` 的唯一写入者是 `AgentExecutor` | 当时 17 个端点里只有 `/api/agent/ask` 被埋点；**产品主链路** `/api/chat/ask`（含 `mode=agent` 走编排的分支）与 `/api/agent/orchestrate` 全都**不计数**；`StatsAgent` 直接调 `tool.execute()` 也绕过了埋点 |

##### 修法：把埋点收敛到「唯一出口」，而不是在各调用点补计数

| 指标 | 唯一写者 | 为什么是它 |
|------|---------|-----------|
| `llmCalls` | `LlmService`（3 个方法） | 全站唯一打 `/v1/chat/completions` 的地方。主 RAG 链、单 Agent ReAct、以及编排里的意图路由 / 查询改写 / 反思评审都必然经过它 ⇒ **不重不漏** |
| `toolCalls` | `ToolRegistry.execute(name, args)`（本轮**新增**的方法） | 全站唯一执行 `Tool` 的地方。`AgentExecutor` 的 ReAct 循环与 `StatsAgent` 的工具直调都收口到这里 ⇒ 修掉"旁路直调不计数"（顺带解除了 `StatsAgent` 对具体 Tool 类型的硬依赖） |
| `queryCount` / `avgCostMs` | 入口层各记一次（`ChatServiceImpl.ask` 覆盖对话页两种模式；`AgentController` 覆盖引擎直连端点） | 入口与"一次用户请求"一一对应，天然只记一次。耗时不再只统计 LLM 段，而是**用户感知的端到端耗时** |

配套的三处结构性调整：

- `AgentMetrics.recordQuery` 的签名**删掉** `llmCalls` / `toolCalls` 两个参数 —— 参数一旦存在，就是在诱导调用方把已知数字再塞一遍（这正是 G-08 的成因）；
- `AgentExecutor` 不再持有 `AgentMetrics`，回归"只编排循环、不管记账"的纯执行器；
- 入口记账放进 `try/finally`：即使链路抛异常，本次提问同样已被受理，也应计入流量。

> 一句话原则：**出口唯一 ⇒ 计数不可能漏、也不可能重**。反过来，把计数写进会被编排层复用的执行器，就是在制造 G-08 那种隐患。
>
> **同一条原则后来还修掉了一处同类问题（熔断挂错层）**：`LlmCircuitBreaker` 原挂在 `AgentExecutor` 上，
> 于是只有"路过该执行器"的引擎直连端点被保护，用户真正在用的主问答链 `/api/chat/ask` 裸奔。
> 判据与指标一样应该是"是否经过 LLM 出口"，而不是"是否经过某个执行器"——现已收口到 `LlmService`，
> 由 A5-02（挂点结构 + 三出口守卫）与 A5-12（三条链路端到端降级 + `llmCalls` 零增长）正反两面取证。

**回归护栏**：`A5-06` 从"断言 `llmCalls > 0`"升级为**精确增量断言**——实测一次验收会话 `queryCount 2→5`、`llmCalls 3→7`、`toolCalls 3→6`，
分解正好 = RAG 链 1 次 LLM（仅查询改写）+ 编排 STATS 链 1 次 LLM / 2 次工具（统计 + 列表）+ ReAct 链 2 次 LLM / 1 次工具，无任何倍数偏差。
数字错了用例立刻变红；后端 73 条、API 51 条同步复测全绿（09-20 第八轮测试结构治理后复跑）。

#### 第三轮（2026-09-18）：「挂错层 / 没有入口」两处同类残留收口

> 下面 2 处**不计入**上面的 9 项（G-01 ~ G-09）：G 编号表是 09-17 定稿轮的快照，
> 追溯改写历史轮计数只会让各文档口径全部漂移。本轮属**同一根因的延续修复**，按同一格式单列说明。

| # | 问题 | 现象与证据 | 修法 |
|---|------|-----------|------|
| ① | **熔断挂错层**：`LlmCircuitBreaker` 只被 `AgentExecutor` 持有 | 与 G-09 **完全同源**——都是"把跨界关注点挂在某一层执行器上"。后果更严重：用户真正在用的主问答链 `/api/chat/ask` 完全没有熔断，LLM 持续失败时它会一路打到超时；而"有熔断"的引擎直连端点反而只有测试在调 | 收口到全站 LLM 唯一出口 `LlmService`（`postChatCompletions` 内 `tryAcquire` / `onSuccess` / `onFailure`），新增 `LlmUnavailableException` 供各链路按自身语义降级：问答返回统一兜底文案且**不写入长期记忆**、路由回落 DOCUMENT、改写退回原句、评审放行、ReAct 返回降级提示。取证：A5-02（挂点结构 + 三出口守卫 + 复位恢复）+ A5-12（三链端到端降级且 `llmCalls` 零增长） |
| ② | **报告生成「实现了但用户不可达」**：`generate_report` 工具只能被 ReAct 循环调用，而 ReAct 只有引擎直连端点 `/api/agent/ask` 够得着 | 与 G-07 同源（功能是对的、但没有任何产品路径会调用它）。旁证：README 写着"助手能自主决定**生成报告**"，而对话页任何问法都触发不到；`ReportAgent`/`AgentType.REPORT` 此前因"装配了却选不中"被删——**删除只解决了死分支，没解决没有入口** | 意图路由扩出第 4 类 `REPORT`；`ReportAgent` 复用 `AgentExecutor` 的 ReAct 循环（不重写第二套循环）；`OrchestratorAgent` 增加 `case REPORT` 并跳过反思重写；`AgentExecutor` 新增 `executeResult(...)` 把工具输出作为证据返回，供落库 sources 与反思核对。取证：A5-13 |

> **这两处共有的判断力（面试可讲）**：`grep 机制名 / 能力名` 扫全部表面，逐处问"它**真覆盖/真触达**这条入口吗"。
> 熔断被四处文档写成"降级矩阵的一员"（读起来像主链路也有），报告生成被写成"助手能自主决定"（读起来像能用）——
> **数字对、定位错，比数字错更难被发现**；而两者的发现方式都是交叉核对，不是任何一次测试执行。

#### 第四轮（2026-09-20）：报告分支的「假产出」——连取证截图都是假的

> 同样**不计入** G-01 ~ G-09：这是 09-18 收口时「接线通了、但产物是假的」留下的隐患，被交付前的一次追问回查出来。

有人问了一句「报告真有实际产出报告吗？我从截图里看不出来」，回查发现：**REPORT 分支从接进产品那天起，从未产出过一份报告正文。**

| 层面 | 现象 | 根因 | 修法 |
|------|------|------|------|
| 配置 | 报告正文 0 字 | `application.yml` 的 `llm.deepseek.max-tokens: 2048` 被**思考模型**的推理阶段吃光——`deepseek-v4-flash` 先写 `reasoning_content` 再写 `content`，实测 `finish_reason=length`、`reasoning_tokens=2048`、`content` 0 字 | `max-tokens: 8192`；`LlmService` 三个出口统一走 `firstMessageOrWarn()`，空 `content` 升为 WARN 并打印 `finish_reason` / reasoning 长度 |
| 链路 | 工具产物被 LLM 概括 | 执行器只用 LLM 的最终回答作答，而 LLM 把两千字的报告概括成一句「报告已生成完成」 | 新增 `Tool.deliverable()`（默认 false、`generate_report` 覆写 true）；执行器识别到可信产物（≥200 字）时**优先以产物原文作答**且不截断 |
| 测试 | 假产出照样全绿 | 断言只校验 `answer` 非空 + `evidence` 非空 + `toolCalls` 增长——「报告已生成」的说明句三项全满足 | A5-13 补 `assertReportBody()`（长度 ≥200 字 **且** 命中 ≥2 个报告结构要素词）；黑盒补 D-05（50 → 51 条） |

**为什么会漏**：三条问法实测产出 **399 / 495 / 662 字**，全是「关于报告的说明」而非报告；09-18 拍的那张「报告生成」截图，拍到的就是这个假产出——**取证截图也会骗人，必须回原始响应体核对**。
修复后实测（`_probe/report_branch_evidence.json`）：产品自然问法即产出 **3094 字**报告正文（命中结构要素 引言/现状/问题/建议），耗时 33879ms。

#### 第五轮（2026-09-20 二轮）：主动审计——「跑通了」不等于「产出了」

> 同样**不计入** G-01 ~ G-09。触发点是那句追问的延伸：「你就不能自己找找错误吗，比如说那个 qa_memory 有没有真落地，agent 链路有没有问题」。这四项都有一个共同形状——**代码在跑、日志在响、用例全绿，但该产出的东西其实没产出**。

| # | 缺陷 | 真相 | 修法 | 取证 |
|---|------|------|------|------|
| 1 | **记忆主键跨重启冲突** —— 号称"跨会话长期记忆"，实为"进程内记忆" | `memoryIdSeq` 是单例**实例字段**且初值恒为 `1`，每次重启（含每轮验收测试）都归零 ⇒ 新记忆主键从 2 重新开始，与上一轮**完全重叠**。Milvus 不强制主键唯一（insert 不报错），仅 query/search 阶段按主键**去重** ⇒ 实测 `qa_memory` 物理 **202 行**、`id=2` 一个主键压了 **38 条**、全部记录只落在 `id=2..10` 九个主键上，**可召回实体仅剩 9 条** | 初值改取 `System.currentTimeMillis()`（时间戳量级且单调递增），不改 schema、不重建 collection，历史数据原地保留 | A4-09 ①（反射断言序列量级 >1e12，防无声回归） |
| 2 | **记忆「向量与入库文本不同源」** —— 注释宣称的截断对存储侧根本没生效 | `MemoryServiceImpl.saveExchange` 把"截断到 200 字"的结果**只用于算向量**，入库却传了完整 `answer`：① 单条记忆可无限长、直撞 `content` 字段上限；② 向量只反映"问题 + 前 200 字"，content 存全文，>200 字回答的后半段对相似度贡献为零 | 先按 **UTF-8 字节上限 2048**（与 Milvus schema 同一常量 `MEMORY_CONTENT_MAX_LEN`）裁剪一次，再让 embedding 与入库**共用这一份文本**；`insertMemory` 签名收敛为 `(vector, userId, content)`，把"入库什么"的契约显式化 | A4-09 ② |
| 3 | **超长记忆静默丢失**（#2 的直接后果） | 入库拼"问题 + 完整回答"无长度保护 ⇒ Milvus 报 `code=1100`（`length: 6124, max length: 2048`）被 `catch` 吞成一行 WARN（实测 2600 字必失败、1900 字正常）。踩坑：`max_length` 计 **UTF-8 字节**（中文 1 字符 = 3 字节），首次按"字符数 ≤ 2048"截断仍得 ~6124 字节、被 A4-09 当场打回 | 按字节边界截断 + WARN；`truncateUtf8()` 保证不切坏多字节字符 | A4-09 ②（实测 2606 字 → 入库 **2047 字节**、可召回） |
| 4 | **HYBRID 组合回答被反思推平** | STATS/REPORT 早已 `skipReflection`，而含**同源工具直出数字**的 HYBRID 漏在特判之外 ⇒ Critic 判"对文档数量的回答自相矛盾…整体回答不够简洁"必触发重写，硬拼接的【数据概况】/【文档解答】+ 文档列表被整段抹平（实测 **200+ 字 → 54 字**） | 与 STATS/REPORT 同处理（`skipReflection = true`），并同步修正 `OrchestratorAgent` 内"只作用于 DOCUMENT / HYBRID"等三处已失效注释 | A5-15（断言回答含【数据概况】与【文档解答】双结构） |

**这一轮的射程教训**：A4-09 原先**直连 `milvusService.insertMemory`**，绕过了 `MemoryServiceImpl` ⇒ 上游那层"截断失效"永远测不到。测试射程决定能发现什么——**从生产入口测，而不是从最方便的那个方法测**。

#### 第六轮（2026-09-20）：系统性梳理 —— 自顶向下四维审计（架构 / 依赖 / 测试 / 文档）

> 同样**不计入** G-01 ~ G-09。这一轮不发新功能，只做三件事：把三处各写一份、且**互不一致**的检索实现收敛为唯一的 `RetrievalChain`（P0）；抽出 `llm/`（LLM 唯一出口）与 `metrics/` 两个模块、controller 归位，打断 2 处包级循环依赖（P1）；把四份文档从「编年式补N」改成**同一套轮次编号**、清掉指向已删文件的悬空引用（P1）。完整四维审计（含 P1/P2/P3 方案与逐条取证）见 `RAG项目系统性梳理报告-2026-09-20.html`。

#### 第七轮（2026-09-20）：结构治理（P2/P3 落地）—— 拆上帝类 / 收口会话读写 / 级联删除

> 承接第六轮排出的方案，**不改行为、只改结构**：`MilvusService`（835 行）按变更原因拆三份（`MilvusCollectionManager` 集合结构面 341 行 / `MilvusService` 文档向量数据面 339 行 / `MilvusMemoryStore` 长期记忆面 253 行；三者各自 `@PostConstruct` 自初始化），**最长文件 835 → 360**；新增 `ChatSessionService` 作为会话/消息**读写唯一出口**（所有按 id 的读写统一过归属校验 ⇒ 越权返回业务码 **403**），`ChatServiceImpl` 321 → **248 行**；文档删除改**级联 + 事务 + 归属校验**（Milvus 向量 → MinIO 对象 → MySQL 分块 → 文档行）；两个 agent 工具不再跨层直摸 Mapper（`agent → document` 的 4 条边全部落在 service/dto）；`rag/impl/` 子包与其余模块的 `service/impl` 约定对齐。
>
> **同时把已修好的 3 条缺口从 A6 改正向回归并移出**（会话越权读/删 → A4-10 / A4-11，删除不级联 → A2-14 / A2-15）——A6 是"断言缺陷当前成立"的台账，**修好即变红**，若只改断言就会从护栏变成掩饰。A6 **7 → 4 条**（编号刻意不重排，它是旧报告的追溯锚点），验收套件 **66 → 67**；复跑 **67/67** + 黑盒 **51/51** 全绿。

#### 第八轮（2026-09-20）：测试结构治理 —— 补六类射程 / A5 拆 6 类 / 黑盒射程分档

> 承接第六轮四维审计里「测试」维度的三条结论（T-1 A5 816 行单文件、T-2 黑盒与 Java 套件同射程重复、**六类射程完全空缺**），**不改行为、只改测试结构与射程**：
>
> **① 新增 A7 域，把六类空缺射程补齐**（`A7_ResilienceAcceptanceTest`，6 条）：并发（4 用户真并发上传+向量化+检索，验证 `ThreadLocal` 用户隔离在真并发下成立）、事务回滚（删除中途抛异常 ⇒ MySQL 侧分块与文档行整体回滚，不留「文档还在、分块已空」的半删态）、级联正向（删后三处终态一致 + 同内容重传主键不冲突）、异步失败（运维闸门拒绝时零残留、陈旧任务号不覆盖快照、任务走专用池）、权限矩阵（14 个受保护端点无 token 全拒 + 越权读/删/改/触发向量化 6 格全 403 零副作用）、超长畸形（超长文件名/标题前置校验 400；emoji+零宽+控制字符内容完整入库）。
>
> **② A5 拆 6 类**：原 `A5_AgentAndBreakerAcceptanceTest`（816 行单文件、塞了 15 个用例 + 大量内联辅助）按**变更原因**拆成 `A5a_BreakerUnit`（2）/ `A5b_ReAct`（2）/ `A5c_Orchestration`（4）/ `A5d_Metrics`（1）/ `A5e_AgentWiring`（3）/ `A5f_ChatIntegration`（3），共用夹具抽进 `A5Support`（143 行），原文件归档到 `_trash-2026-09-20/`。用例总数与断言不变（15），换的只是承载结构。
>
> **③ 黑盒射程分档**：主套件 43 条 + 补充 8 条逐条标注 `unique` / `replaceable`（`_probe/api_suite_tiers.mjs`），当前**唯一射程 3 条**（C-08 更新标题异常语义 / D-04 编排 DOCUMENT 分支端到端 / S-03 前后端白名单一致性），其余可被 Java 套件交叉验证。分档表由 `_run_api_suite.py` 在收口处复核，**新增用例漏登记会直接告警**。
>
> **★ 首跑即抓到 2 处真缺陷**（已修，**单列、不计入 G-01~G-09**）：① **IDOR 写越权** —— `POST /api/document/embed/{id}` 只校验文档存在、不校验归属，任何登录用户拿他人文档 id 就能替对方触发向量化（改状态位 + 写向量库）；同源方法 `reparseDocument` 一并补校验。② **超长输入落 500** —— 超长文件名（> `VARCHAR(200)`）与会话标题（> `VARCHAR(100)`）不命中任何业务校验，一路走到 INSERT/UPDATE 才被列上限拒绝 ⇒ 把「你输入太长」误报成「服务端故障」。两处与 G-01~G-09 同源：**不是代码写错，而是射程没覆盖**（越权只逐点补过删除与会话，非法输入只按类型测过）。
>
> 套件 **67 → 73**、验收域 **6 → 7**、测试类 9 → 13（16 文件 / 3834 行）；主代码 **81 类 / 6493 行**（第七轮 6448，第八轮修 2 缺陷 +45）；复跑 **73/73** + 黑盒 **51/51** 全绿。

### 交付前代码审计：删掉 2 个孤立类 + 4 处冗余 import

功能测试全绿后再做了一遍「孤立代码审计」——统计每个类的被引用次数，并区分「Spring 注解装配」（源码里本就不会出现类名，属正常）与「真无引用」。查出并清掉：

| 清掉的东西 | 为什么是死代码 |
|-----------|--------------|
| `eval/EvalRunner.java`（含内部重复的 `EvalCase`） | 无任何引用，`main()` 只往空 `List` 打印「待填充」，真正的 20 题评估早在 `test/eval/EvalRunnerTest` 里跑通；内部 `EvalCase` 还与 test 侧同名类重复定义。删除后 `eval` 包只存在于 test 侧（唯一评估入口） |
| 4 处未使用 import | `ToolRegistry`（ConcurrentHashMap）、`AuthController`（BusinessException）、`A3_RetrievalAcceptanceTest`（assertEquals）、`AcceptanceSupport`（SourceHttpMessageConverter） |

> 这两项**没有计入上面的缺陷数**：它们不改变任何接口行为，是收尾清理而非功能缺陷。
> 目的是让「写了却没人调」的代码在仓库里归零——面试官随手点开一个类，都应该能问出「谁在调它」并得到答案。

**后续迭代：被删的 `ReportAgent` 又加回来了，但这次它不再是死分支。**
审计当时删它的理由成立：`RouterService` 只能产出 `DOCUMENT/STATS/HYBRID`，`switch` 里也没有 `case REPORT`
⇒ 它 `@Component` 进了 `List<Agent>` 却永远选不中（"装配了却选不中"）。但**删除只解决了"死分支"，没解决根因**：
`generate_report` 只能被 ReAct 循环调用，而 ReAct 原先只有引擎直连端点够得着 ⇒ 报告生成对用户等于不存在。
修复方式是把路由扩出第 4 类 `REPORT`，并让 `ReportAgent` 直接复用 `AgentExecutor` 的 ReAct 循环（不重写第二套循环）：
死分支变成活分支，报告能力与 ReAct 能力一起接进对话页。**"删掉重复实现"和"补上缺失入口"是两件事，前者做完了不等于后者做了。**
详见上文「Agentic 问答」。

## 配置说明

### 环境变量（.env）

| 变量 | 说明 | 默认/示例 |
|------|------|-----------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `rag123456` |
| `MYSQL_DATABASE` | 数据库名 | `rag_kb` |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | MinIO 凭据 | `minioadmin` |
| `DEEPSEEK_API_KEY` | DeepSeek 大模型密钥（必填） | — |
| `ZHIPU_API_KEY` | 智谱密钥（Embedding/Rerank，必填） | — |
| `JWT_SECRET` | JWT 签名密钥 | 开发可用默认值，生产必改 |

> ⚠️ **凭据漂移陷阱（实测踩坑）**：MinIO / MySQL 的凭据只在**容器首次初始化**时生效。
> 如果容器已用旧凭据创建过 Volume，之后改 `.env` 里的 `MINIO_ROOT_USER/PASSWORD` 并不会改变容器内实际凭据，
> 后端拿着新凭据调 MinIO 会一路 401/400（表现为"上传文档失败"）。验收前请确认 `.env` 与
> `docker inspect <容器>` 中的实际环境变量一致；不一致时要么改 `.env` 回滚，要么删 Volume 重建容器。

### 检索与 Agent 调优（application.yml → `rag.*`）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `rag.chunk-size` / `chunk-overlap` | 512 / 64 | 分块滑动窗口 |
| `rag.retrieval.hybrid-alpha` | 0.7 | 混合检索中稠密分权重（稀疏占 1-alpha） |
| `rag.retrieval.recall-top-k` | 20 | 混合检索召回条数（重排前） |
| `rag.retrieval.rerank-top-n` | 5 | 重排后进入 Prompt 的条数 |
| `rag.agent.max-iterations` | 5 | ReAct 循环最大轮数 |
| `rag.agent.min-score` | 0.35 | 检索结果最低相似度阈值 |
| `rag.agent.breaker-failure-threshold` / `breaker-open-millis` | 5 / 60000 | 熔断：连续失败次数 / 熔断时长(ms) |
| `rag.agent.critic-max-retry` | 1 | 多 Agent 反思重写最大次数 |
| `rag.prompt-template` | （内置） | 问答 Prompt 模板，`{context}`/`{question}` 占位 |

模型与中间件连接配置（`llm.*` / `embedding.*` / `milvus.*` / `minio.*`）同样在 `application.yml`，均支持环境变量覆盖。

## 项目结构

```
rag-knowledge-base/
├── backend/                              Spring Boot 后端
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/liushuwen/rag/
│       │   ├── RagKnowledgeBaseApplication.java   启动入口
│       │   ├── auth/                     🔐 认证模块
│       │   │   ├── controller/AuthController.java
│       │   │   ├── entity/User.java
│       │   │   ├── mapper/UserMapper.java
│       │   │   └── service/UserService.java + impl/UserServiceImpl.java
│       │   ├── document/                 📄 文档模块
│       │   │   ├── controller/DocumentController.java
│       │   │   ├── entity/Document.java + DocumentChunk.java
│       │   │   ├── mapper/DocumentMapper.java + DocumentChunkMapper.java
│       │   │   └── service/
│       │   │       ├── DocumentService.java + impl/DocumentServiceImpl.java   编排全流程
│       │   │       ├── MinioService.java            MinIO 文件操作
│       │   │       ├── DocumentParserService.java   PDF/Word/MD/TXT 解析
│       │   │       ├── DocumentChunkService.java    滑动窗口分块
│       │   │       ├── EmbeddingService.java        智谱向量化
│       │   │       ├── MilvusService.java           文档向量 CRUD + 混合检索（数据面）
│       │   │       ├── MilvusCollectionManager.java 集合结构：建库/探测/影子表切换（结构面）
│       │   │       ├── MilvusMemoryStore.java       长期记忆库 qa_memory（独立集合 + 字节级裁剪）
│       │   │       └── IndexRebuildService.java + impl/ + Worker   索引重建（运维型端点 · @Async）
│       │   ├── chat/                     💬 问答模块
│       │   │   ├── controller/ChatController.java
│       │   │   ├── entity/ChatSession.java + ChatMessage.java
│       │   │   ├── mapper/ChatSessionMapper.java + ChatMessageMapper.java
│       │   │   └── service/
│       │   │       ├── ChatService.java + impl/ChatServiceImpl.java      问答编排（提问→召回→生成→落库）
│       │   │       └── ChatSessionService.java + impl/                  会话/消息读写唯一出口（含归属校验 403）
│       │   ├── llm/                      🧠 LLM 接入层（全站唯一出口）
│       │   │   ├── LlmService.java                  DeepSeek 调用（chat / chatWithSystem / chatWithTools）
│       │   │   ├── LlmCircuitBreaker.java           熔断器（挂唯一出口 ⇒ 全链路覆盖）
│       │   │   ├── LlmUnavailableException.java     熔断专属异常（各链路按自身语义降级）
│       │   │   └── Tool.java                        工具契约（Function Calling 入参 Schema）
│       │   ├── metrics/                  📈 指标模块
│       │   │   ├── AgentMetrics.java                指标埋点（按天累计，唯一写者口径）
│       │   │   └── controller/MetricsController.java  /api/metrics/today
│       │   ├── agent/                    🤖 Agent 模块
│       │   │   ├── ToolRegistry.java                工具注册表（工具执行唯一出口）
│       │   │   ├── QueryDocumentStatsTool.java      工具：文档统计
│       │   │   ├── QueryDocumentListTool.java       工具：文档列表
│       │   │   ├── GenerateReportTool.java          工具：报告生成
│       │   │   ├── AgentExecutor.java               ReAct 循环执行器（≤5 轮）
│       │   │   ├── Agent.java + AgentResult.java   Agent 契约（回答 + 证据片段）
│       │   │   ├── DocumentAgent / StatsAgent / ReportAgent   子 Agent（DOCUMENT / STATS / REPORT）
│       │   │   ├── OrchestratorAgent.java           多 Agent 编排（主管分派 + 反思评审）
│       │   ├── rag/                      🔍 检索增强模块
│       │   │   ├── impl/                  五个 Service 实现（Critic / Memory / QueryRewriter / Rerank / Router）
│       │   │   ├── Route.java + RouterService.java          意图路由（DOCUMENT / STATS / REPORT / HYBRID）
│       │   │   ├── QueryRewriterService.java                查询改写
│       │   │   ├── RerankService.java                       重排序
│       │   │   ├── CriticService.java + Critique            反思评审
│       │   │   ├── MemoryService.java                       长期记忆接口层（qa_memory）
│       │   │   └── RetrievalChain.java                      ⭐ 全站唯一检索链（向量化→用户隔离→记忆→改写→混合检索→重排→阈值过滤）
│       │   ├── common/                   🔧 Result / BusinessException / GlobalExceptionHandler / UserContext
│       │   └── config/                   ⚙️ JwtUtil / JwtInterceptor / WebMvcConfig / RestTemplateConfig
│       │       ├── MilvusConfig / MinioConfig / RagProperties(@ConfigurationProperties)
│       │       └── MybatisPlusConfig / MyMetaObjectHandler / CorsConfig
│       ├── main/resources/application.yml
│       └── test/java/com/liushuwen/rag/
│           ├── acceptance/                ✅ 验收套件（真实 HTTP，73 用例 / 7 域）
│           │   ├── AcceptanceSupport.java        基类：真实 RestTemplate + 环境指纹 + 向量可见性等待
│           │   ├── A5Support.java                 A5 共用夹具（检索语料 / 熔断开关 / 反射取证）
│           │   ├── A1_AuthAndContractAcceptanceTest.java      认证与统一契约（13）
│           │   ├── A2_IngestionAcceptanceTest.java            离线入库（15）
│           │   ├── A3_RetrievalAcceptanceTest.java            检索链路与隔离（8）
│           │   ├── A4_ChatFlowAcceptanceTest.java             在线问答全链路（11）
│           │   ├── A5a_BreakerUnitAcceptanceTest.java         熔断单元（2）
│           │   ├── A5b_ReActAcceptanceTest.java               ReAct 引擎（2）
│           │   ├── A5c_OrchestrationAcceptanceTest.java       编排层（4）
│           │   ├── A5d_MetricsAcceptanceTest.java             指标联动（1）
│           │   ├── A5e_AgentWiringAcceptanceTest.java         Agent 装配（3）
│           │   ├── A5f_ChatIntegrationAcceptanceTest.java     对话页集成与降级（3）
│           │   ├── A6_KnownGapAcceptanceTest.java             已知缺口固化（4 · 缺口台账）
│           │   └── A7_ResilienceAcceptanceTest.java           鲁棒性与并发（6 · 第八轮新增域）
│           └── eval/
│               └── EvalRunnerTest.java       唯一评估入口（读 docs/eval/questions.json，20 题）
│
├── frontend/                             Vue3 前端
│   ├── vite.config.js                    /api 代理 → localhost:18080
│   └── src/
│       ├── main.js / App.vue
│       ├── router/index.js               路由 + 登录守卫
│       ├── api/                          index.js(Axios+token拦截器) / auth.js / document.js / chat.js
│       ├── stores/                       auth.js / chat.js (Pinia)
│       └── views/                        LoginView / DocumentView / ChatView
│
├── docker/mysql/
│   ├── init/init.sql                     建表脚本（容器首次启动自动执行）
│   └── migration_week6.sql               Week6 前旧库迁移脚本（加 category 列）
├── docs/eval/questions.json              检索评估集（20 题，配合 EvalRunnerTest）
├── docker-compose.yml                    一键编排 MySQL + MinIO + etcd + Milvus
├── .env.example                          环境变量模板
└── README.md
```

## 数据模型

**MySQL**（`docker/mysql/init/init.sql` 自动建表）：

| 表名 | 说明 |
|------|------|
| `user` | 用户（用户名 / BCrypt 密码 / 昵称 / 邮箱） |
| `document` | 文档（标题 / 文件名 / 类型 / 大小 / MinIO 路径 / 分块数 / 向量化状态 / 分类 / userId） |
| `document_chunk` | 分块（文档 ID / 序号 / 内容 / 字符数） |
| `chat_session` | 会话（userId / 标题 / 时间） |
| `chat_message` | 消息（会话 ID / 角色 / 内容 / 来源引用 JSON） |

**Milvus collections**：

| Collection | 说明 |
|------------|------|
| `rag_document_chunks` | 文档分块向量（稠密 2048 维 + `bm25_vector` 稀疏字段 + BM25 Function） |
| `qa_memory` | 长期记忆问答对 |

> 密码使用 BCrypt 加密存储，无明文测试用户，需通过注册接口创建账号。

## API 一览

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/auth/register` | 用户注册 | 否 |
| POST | `/api/auth/login` | 用户登录（返回 user + token） | 否 |
| GET | `/api/auth/me` | 获取当前用户信息 | 是 |
| POST | `/api/document/upload` | 上传文档（`file` + 可选 `category`，自动解析分块入库） | 是 |
| GET | `/api/document/list` | 文档列表（按用户隔离） | 是 |
| DELETE | `/api/document/{id}` | 删除文档（**级联**：Milvus 向量 → MinIO 对象 → MySQL 分块 → 文档行，事务包裹；非归属者 403） | 是 |
| POST | `/api/document/embed/{id}` | 触发向量化入库 | 是 |
| POST | `/api/document/rebuild-index` | **受理索引重建（异步）**：仅 `ADMIN` 角色；body 需带 `{"confirm":"CONFIRM-REBUILD"}`；立即返回任务快照，长任务在后台线程执行 | 是 |
| GET | `/api/document/rebuild-index` | 查询重建任务状态与当前账号的运维权限（`{allowed, task}`，无权时 `task=null`）；与 POST **同路径**，故端点路径总数不变 | 是 |
| POST | `/api/chat/session` | 创建对话会话 | 是 |
| GET | `/api/chat/sessions` | 会话列表 | 是 |
| PUT | `/api/chat/session/{sessionId}/title` | 修改会话标题 | 是 |
| DELETE | `/api/chat/session/{sessionId}` | 删除会话（级联删除消息） | 是 |
| POST | `/api/chat/ask/{sessionId}` | 智能问答（body：`{"question":"..."}`；可选 `"mode":"agent"` 走多 Agent 编排） | 是 |
| GET | `/api/chat/history/{sessionId}` | 获取会话历史消息 | 是 |
| POST | `/api/agent/ask` | ReAct 引擎直连端点（直连 `AgentExecutor`；产品入口是对话页「深度思考」） | 是 |
| GET | `/api/metrics/today` | 今日指标（问答量 / 平均耗时 / LLM / 工具调用） | 是 |

> **端点总数：16 个**（认证 3 / 文档 5 / 会话问答 6 / Agent 1 / 指标 1）。09-17 时为 17 个（Agent 2），09-18 收敛动作删除了与产品入口完全重叠的 `POST /api/agent/orchestrate` ⇒ 16。
> 计算口径按**路径**数（`/v3/api-docs` 的 `len(paths)`）：09-20 索引重建端点新增了同路径的 `GET`（查状态），故文档模块仍是 5 条路径、6 个方法，总数不变。

> 完整接口文档：http://localhost:18080/doc.html （Knife4j）。认证接口在页面右上角「Authorize」输入 `Bearer <token>` 统一配置。

## 开发里程碑

| 阶段 | 内容 | 状态 |
|------|------|------|
| 第 1-2 周 | 项目搭建、Docker 编排、文档上传解析、MinIO 存储 | ✅ |
| 第 3-4 周 | Embedding 向量化、Milvus 检索、RAG 在线问答 | ✅ |
| 第 5-6 周 | 前端交互、JWT 认证、数据隔离、文档分类 | ✅ |
| 第 7 周 | 项目文档与评估 | ✅ |
| 第 8 周 | Agentic RAG 演进：混合检索 / Rerank / 查询改写 / ReAct / 意图路由 / 多 Agent / 长期记忆 / 降级熔断 / 评估 | ✅ |
| 第 9 周 | 验收测试重写（A1–A6 + 评估，当轮 60 用例真实 HTTP；09-18 收口后 63）+ 全量功能黑盒测试（50 API 用例 + 28 张 UI 截图，全程无 5xx）+ 修复 15 处缺陷（6 + G-01 ~ G-09）+ **Agent 模块接入对话页（深度思考开关 → mode=agent 落库回读）** + **指标埋点口径重构（埋点收敛到唯一出口：LlmService / ToolRegistry.execute / 入口层）** + 文档与实现对齐 + 已知缺口固化 | ✅ |
| 第 9 周·收口 | **熔断收口到唯一出口**（`LlmService`，从此覆盖主问答链 / 编排链 / ReAct 链全链路，新增 `LlmUnavailableException` 供各链路按自身语义降级）+ **报告生成接入对话页**（意图路由第 4 类 `REPORT` + `ReportAgent` 复用 ReAct 引擎 + `AgentExecutor.executeResult` 返回证据）+ 验收套件扩到 **63 用例**（该轮口径；09-20 补 A2-13 后为 64、二轮后为 66）（新增 A5-02 改造 / A5-12 熔断端到端 / A5-13 REPORT 分支 / A5-14 四类路由 + 单引擎实例（编排与引擎直连端点 assertSame））| ✅ |
| 第 9 周·收口Ⅲ（09-20） | **索引重建从"无门槛的全局破坏性接口"收口为受控运维能力**（三处缺陷一并修复）：① 系统无角色概念 ⇒ 新增 `user.role` + `ADMIN` 准入，修掉「任何登录用户都能 drop 主 collection 并回放所有用户文档」；② `drop` 在 `try` 之外、先删后灌 ⇒ 改为**影子表 + 改名切换**（回放期间旧表继续服务，索引真空期从实测 72.3s 量级压到毫秒级），结构已就绪时走**原地逐文档重灌**（零真空期）；③ 同步长任务阻塞请求线程 ⇒ 新增 `AsyncConfig`/`IndexRebuildService`/`IndexRebuildWorker`，改为 **`@Async` 异步受理 + 同路径 `GET` 轮询进度**，并在前端文档页补上运维入口；同时补验收用例 **A2-13**（把"运维型端点"纳入射程，套件 **63 → 64**）| ✅ |
| 第 9 周·收口Ⅳ（09-20）· 运维链路实测取证 | `_probe/rebuild_ops_evidence.json` + `verify_rebuild_ops.mjs`（两条路径各跑一轮真实重建）。**warm（原地重灌）**：受理 **15ms** 返回（修复前同步 72300ms）、非 ADMIN 提交 HTTP 400/code 403、无权时 `task=null`、缺确认串 400/code 400、运行中重复提交 400/code 409、354/354 文档回放完成（243.2s），**全程每 1.2s 采样主 collection 恒存在且可查 ⇒ 真空期 0ms** | ✅ |
| 第 9 周·收口Ⅳ（09-20）· cold 路径（影子表切换）取证 | 旧结构主表（无 `bm25_vector`）⇒ 先建影子表 `rag_document_chunks__rebuild` 全量回放，**回放 244.9s 期间 201 次采样旧表恒定可查（≥5 行）⇒ 检索全程不中断**；回放完成才改名顶替主表，切换后主表已带 `bm25_vector`、影子表自动清理，真空期 0ms | ✅ |
| 第 9 周·收口Ⅴ（09-20）· 记忆与 HYBRID 三处缺陷 | 主动审计（起因：用户质疑"qa_memory 到底有没有真落地、agent 链路有没有问题"）挖出并修复：① **记忆主键跨重启冲突** —— `memoryIdSeq` 是单例实例字段且初值恒为 1，每次重启（含每一轮验收测试）id 又从 2 重新开始，与历史记忆主键完全重叠；Milvus 虽允许重复主键，但 query/search 按主键去重 ⇒ 实测 qa_memory 物理 202 行、`id=2` 一个主键上压了 38 条记录、全部记录只落在 id=2..10 九个主键上，**可召回实体仅剩 9 条，"跨会话长期记忆"实际退化为"进程内记忆"**；改为初值取时间戳（不改 schema、不重建 collection）。② **超长记忆静默丢失** —— content 字段上限是 2048 **字节**（UTF-8，不是字符数），而入库拼的是"问题+完整回答"且无长度保护，超长时 Milvus 报 code=1100 又被 `catch` 吞成一行 WARN ⇒ 记忆无声消失（实测 2600 字必失败、1900 字正常）；改为按 **UTF-8 字节边界**截断并告警（首次按"字符数"截断仍被 A4-09 实测打回——日志明示 6124 字节，中文 1 字符 = 3 字节）。③ **HYBRID 组合回答被反思重写推平** —— STATS 早已 `skipReflection`（"重写会把准确数字换成模糊复述"），但含同样工具直出数字的 HYBRID 漏在特判之外，Critic 见两段来源天然矛盾（数据段"有 1 篇文档" vs 检索段"资料未提及"）必判不合格并重写 ⇒ 硬拼接的【数据概况】/【文档解答】与文档列表被整段抹平（实测 200+ 字结构化成文 → 54 字口语概述）；与 STATS 同处理跳过反思（修复后 209 字、结构与文档列表完整）。取证：A4-09（记忆主键量级 + 超长入库防线）、A5-15（HYBRID 结构保全）、`_probe/hybrid_branch_evidence.json`、`_probe/r26_verify_evidence.json`；套件 **64 → 66** | ✅ |
| 第 9 周·收口Ⅵ（09-20）· **测试结构治理（第八轮）** | 三项测试侧重构，**不改行为、只改测试结构与射程**：① **新增 A7 域补六类空缺射程**（并发 / 事务回滚 / 级联正向 / 异步失败 / 权限矩阵 / 超长畸形）——首跑 6 条即抓到 **2 处真缺陷**（`POST /api/document/embed/{id}` 写越权 IDOR、超长文件名/会话标题落 HTTP 500），当场修复并固化为 A7-05 / A7-06 正向护栏；② **A5 按变更原因拆 6 类**（A5a 熔断单元 / A5b ReAct / A5c 编排 / A5d 指标 / A5e 装配 / A5f 对话集成）+ 抽 `A5Support` 共用夹具，原 816 行单文件归档到 `_trash-2026-09-20/`；③ **黑盒 51 条按射程分档**（`_probe/api_suite_tiers.mjs`：唯一射程 3 / 可交叉验证 48 / 未登记 0），并在 `_run_api_suite.py` 收口处复核，防止「新增用例却漏登记」静默通过。套件 **67 → 73**、验收域 **6 → 7**（测试类 9 → 13）；复跑 **73/73** + 黑盒 **51/51** 全绿 | ✅ |

## 常见问题

### Docker 拉取镜像超时？

Docker Desktop → Settings → Docker Engine 配置镜像加速器：

```json
{
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://docker.mirrors.163.com"
  ]
}
```

> quay.io 镜像（etcd）如拉取慢，可在 Git Bash 中设置 `DOCKER_CONFIG=/tmp/docker-config docker pull`。

### Maven 下载依赖很慢？

在 `~/.m2/settings.xml` 配置阿里云镜像：

```xml
<mirror>
    <id>aliyunmaven</id>
    <mirrorOf>*</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

### 端口被占用？

| 端口 | 服务 | 修改位置 |
|------|------|----------|
| 18080 | Spring Boot | `application.yml` → `server.port` |
| 5173 | Vue3 前端 | `vite.config.js` → `server.port` |
| 3307 | MySQL | `docker-compose.yml` 端口映射 |
| 9000/9002 | MinIO | `docker-compose.yml` 端口映射 |
| 19530/9091 | Milvus | `docker-compose.yml` 端口映射 |

### Milvus 从 2.4 升级到 2.5（启用混合检索）？

| 项 | 2.4（旧） | 2.5（新） | 说明 |
|----|-----------|-----------|------|
| 服务端镜像 | `milvusdb/milvus:v2.4.10` | `v2.5.16` | `docker-compose up -d` 重拉镜像 |
| Java SDK | `milvus-sdk-java 2.4.1` | `2.5.14` | SDK 改用 Gson，项目已显式声明 fastjson 并适配 |
| BM25 Function | 不支持 | 支持 | 旧 collection 需重建（由 `ADMIN` 角色调用 `POST /api/document/rebuild-index`，body 带 `{"confirm":"CONFIRM-REBUILD"}`；自动回放已向量化文档） |

> v1 gRPC 协议向后兼容，不重建 collection 也能继续用（混合检索自动降级为纯稠密）。

### Week6 之前的数据库需要迁移（加 category 列）？

```bash
docker exec -i rag-mysql mysql -uroot -p<你的密码> rag_kb < docker/mysql/migration_week6.sql
```
