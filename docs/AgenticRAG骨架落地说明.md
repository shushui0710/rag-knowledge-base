# Agentic RAG 演进骨架落地说明

> 配套文档：`docs/AgenticRAG演进辅导方案.md`（概念讲解 + 完整 TODO 设计）
> 本文件：已落地骨架的**文件清单**、**TODO 分布**、**里程碑验证方法**
> 验证状态：✅ `mvn compile` BUILD SUCCESS（68 个源文件，2026-08-12）

---

## 一、已生成骨架总览（新增 29 个文件 + 修改 4 个）

### M1 基础设施（阶段1/5 共用）
| 文件 | 职责 | 状态 |
|------|------|------|
| `config/RagProperties.java` | Agent/检索配置组（@ConfigurationProperties） | ✅ 可用 |
| `agent/AgentMetrics.java` | 指标埋点（问答数/耗时/LLM调用/工具调用） | ✅ 可用 |
| `agent/LlmCircuitBreaker.java` | LLM 熔断器（连续失败→熔断→兜底） | ✅ 可用 |
| `rag/Route.java` | 意图路由枚举（DOCUMENT/STATS/HYBRID） | ✅ 可用 |

### M2 检索优化（阶段2）
| 文件 | 职责 | 状态 |
|------|------|------|
| `rag/RerankService.java` + `RerankServiceImpl.java` | 粗召回→精排（骨架：按原分数取TopN） | ⏳ TODO 2-2 |
| `rag/QueryRewriterService.java` + `QueryRewriterServiceImpl.java` | 查询改写（骨架：返回原问题） | ⏳ TODO 2-3 |
| `MilvusService.hybridSearch()`（修改） | 稠密+稀疏双路召回融合（骨架：退化为纯稠密） | ⏳ TODO 2-1 |
| `MilvusService.deleteByDocumentId()`（修改） | 按文档删向量（骨架：仅日志） | ⏳ TODO 1-1b |

### M3 Agentic 化（阶段3，差异化核心）
| 文件 | 职责 | 状态 |
|------|------|------|
| `agent/Tool.java` | 工具抽象接口（name/description/schema/execute） | ✅ 可用 |
| `agent/ToolRegistry.java` | 工具注册表（Spring 自动收集 List<Tool>） | ✅ 可用 |
| `agent/QueryDocumentStatsTool.java` | 工具1：文档统计（含用户隔离） | ✅ **已实现**（第一个完整工具） |
| `agent/QueryDocumentListTool.java` | 工具2：文档列表（含分类过滤参数） | ⏳ TODO 3-1b |
| `agent/GenerateReportTool.java` | 工具3：报告生成（带 topic 参数） | ⏳ TODO 4-1 |
| `agent/AgentExecutor.java` | ReAct 执行器（骨架：直通 LLM + 熔断 + 埋点） | ⏳ TODO 3-3 |
| `LlmService.chatWithTools()`（修改） | Function Calling 请求/解析（骨架：退化为 chat） | ⏳ TODO 3-2 |
| `controller/AgentController.java` | POST /api/agent/ask（单Agent）、/orchestrate（多Agent） | ✅ 可用 |

### M4 多 Agent（阶段4）
| 文件 | 职责 | 状态 |
|------|------|------|
| `rag/MemoryService.java` + `MemoryServiceImpl.java` | 长期记忆（骨架：空实现） | ⏳ TODO 4-2 |
| `rag/CriticService.java` + `CriticServiceImpl.java` + `Critique.java` | 反思评审（骨架：默认放行） | ⏳ TODO 4-3 |
| `agent/Agent.java` | 多 Agent 接口（AgentType 枚举） | ✅ 可用 |
| `agent/DocumentAgent.java` | 文档问答 Agent（骨架：朴素 RAG 可跑） | ⏳ TODO 4-1 |
| `agent/StatsAgent.java` | 数据查询 Agent（骨架：转给 AgentExecutor，直通 LLM） | ⏳ TODO 4-1 |
| `agent/ReportAgent.java` | 报告生成 Agent（骨架：占位返回） | ⏳ TODO 4-1 |
| `agent/OrchestratorAgent.java` | 主管 Agent（路由→分派，骨架可跑） | ⏳ TODO 4-1 |

### M5 生产化（阶段5）
| 文件 | 职责 | 状态 |
|------|------|------|
| `controller/MetricsController.java` | GET /api/metrics/today | ✅ 可用 |
| `eval/EvalRunner.java` | 评估脚本（测试集 + 命中率主流程占位） | ⏳ TODO 5-1 |

### 增量更新（阶段1）
| 文件 | 职责 | 状态 |
|------|------|------|
| `DocumentService.reparseDocument()`（修改接口） | 增量更新入口 | ✅ 接口就绪 |
| `DocumentServiceImpl.reparseDocument()`（修改实现） | 删旧→重建（骨架：仅重置状态） | ⏳ TODO 1-1 |

### 配置（application.yml 新增）
```yaml
rag:
  agent:
    max-iterations: 5
    min-score: 0.35
    breaker-failure-threshold: 5
    breaker-open-millis: 60000
    critic-max-retry: 1
  retrieval:
    hybrid-alpha: 0.7
    recall-top-k: 20
    rerank-top-n: 5
    embed-cache-limit: 5000
```

---

## 二、按你的节奏逐步填充：TODO 清单（从易到难）

| 顺序 | TODO | 难度 | 文件 | 填充后效果 |
|------|------|:---:|------|-----------|
| 1 | 1-1 增量更新 | ⭐ | DocumentServiceImpl | 重传文档只重算该文档 |
| 2 | 1-1b 删向量 | ⭐ | MilvusService | Milvus 按 document_id 布尔表达式删除 |
| 3 | 2-1 混合检索 | ⭐⭐ | MilvusService | 精确匹配（编号/型号）命中率提升 |
| 4 | 2-2 Rerank | ⭐⭐ | RerankServiceImpl | 召回 20→精排 Top5 |
| 5 | 2-3 查询改写 | ⭐⭐ | QueryRewriterServiceImpl | 口语问题召回提升 |
| 6 | 3-2 Function Calling | ⭐⭐⭐ | LlmService | DeepSeek 返回结构化 tool_calls |
| 7 | 3-3 ReAct 循环 | ⭐⭐⭐ | AgentExecutor | "查库/查文档"自主决策多步执行 |
| 8 | 3-1b 文档列表工具 | ⭐⭐ | QueryDocumentListTool | 第二个真实工具 |
| 9 | 4-1 多 Agent 分派 | ⭐⭐⭐ | 各 Agent + Orchestrator | 主管分工协作 |
| 10 | 4-2 长期记忆 | ⭐⭐ | MemoryServiceImpl | 跨会话"记得"历史问答 |
| 11 | 4-3 反思自修正 | ⭐⭐⭐ | CriticServiceImpl | 回答不达标自动重写 |
| 12 | 5-1 评估集 | ⭐⭐ | EvalRunner | "命中率 X%"数据说话 |

---

## 三、里程碑验证方法（每个阶段完成后自测）

### M1 验证（骨架本身）
```bash
mvn compile -DskipTests   # 应 BUILD SUCCESS
```
启动后访问（需登录 token，与现有接口一致）：
- `POST /api/agent/ask`  `{"question":"你好"}` → 应返回 LLM 回答（骨架直通）
- `GET  /api/metrics/today` → 返回指标 JSON

### M2 验证（检索优化）
- 上传含"编号/型号"类内容的文档 → 问"查一下 E217 相关规范" → 混合检索能命中
- 建 20 题测试集，改前改后跑 EvalRunner 对比命中率

### M3 验证（Agentic 化）⭐
> ⚠️ 前置：以下两条依赖 **TODO 3-2（chatWithTools 返回 LlmResponse）** 和 **TODO 3-3（ReAct 循环）** 完成后才会生效。
> 骨架阶段 AgentExecutor 是"直通 LLM"，不会真正调用工具。
- `POST /api/agent/ask` `{"question":"知识库里有多少文档？"}` → 助手调 `query_document_stats` 工具返回真实数字
- 日志出现 `[AgentMetrics] tool call: query_document_stats`

### M4 验证（多 Agent）
- `POST /api/agent/orchestrate` `{"question":"统计一下文档数，顺便查应急预案火灾怎么处理"}` → 主管分派 STATS + DOCUMENT
- 第二次问"上次说的安全规范是啥" → 长期记忆召回

### M5 验证（生产化）
- 连续问 5 次（故意改错 API key）→ 熔断生效，返回兜底文案
- `GET /api/metrics/today` → 有数据

---

## 四、修改过的现有文件（4 个，均为增量添加，不影响旧功能）

1. `LlmService.java` — 加 `chatWithTools()` + `ToolCall` DTO（旧 `chat()` 不动）
2. `MilvusService.java` — 加 `deleteByDocumentId()` + `hybridSearch()`（旧 `search()` 不动）
3. `DocumentService.java` / `DocumentServiceImpl.java` — 加 `reparseDocument()`
4. `application.yml` — 加 `rag.agent.*` / `rag.retrieval.*`（旧配置不动）

---

## 五、使用提示

- **每个 TODO 都有完整注释**：提示 + 面试考点 + 伪代码，先自己写再看参考答案（参考答案在辅导方案文档里）
- **骨架保证可运行**：所有占位实现都是"安全默认值"（不抛异常、不炸流程），你随时可以 `mvn spring-boot:run` 起服务测试
- **新工具上线**：只需新增一个实现 `Tool` 接口的 @Component，注册表自动感知
- **面试讲法**：先讲 1-1/2-1/2-2（检索工程），重点讲 3-2/3-3（Function Calling + ReAct），再补 4-1/4-3（多 Agent + 反思）——正好 3 分钟亮点叙事
