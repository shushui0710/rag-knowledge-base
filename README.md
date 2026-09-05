# RAG 智能知识库问答系统

基于 RAG（检索增强生成）的企业级智能知识库问答平台：文档上传解析、分块向量化入库、混合检索与大模型问答，附带 JWT 认证与多用户数据隔离，并已演进为 **Agentic RAG** —— 通过 Function Calling + ReAct 循环、意图路由与多 Agent 编排，让助手能自主决定"检索文档 / 查询统计 / 生成报告"。

核心亮点：

- **混合检索**：Milvus 2.5 内置 BM25 Function，稠密 + 稀疏双路召回，加权融合（alpha=0.7）后经智谱 Rerank 精排（召回 20 → 精排 5），并按最低相似度 0.35 过滤
- **Agentic 能力**：ReAct 循环（≤5 轮）+ 工具调用 + 意图路由（DOCUMENT / STATS / HYBRID）+ 多 Agent 编排与反思重写
- **生产化设计**：长期记忆（qa_memory）、三层降级 + LLM 熔断器（5 次/60s）、指标观测、评估集回归测试

## 核心功能

| 模块 | 功能 |
|------|------|
| 用户认证 | 注册 / 登录（JWT + BCrypt），`JwtInterceptor` + ThreadLocal 登录态，路由守卫 |
| 文档管理 | 上传（PDF/Word/MD/TXT，支持分类）、解析分块、向量化入库、删除（级联清理向量）、重建混合索引 |
| 智能问答 | 多会话管理、来源引用、Markdown 渲染、历史记录、会话标题修改 |
| Agentic 问答 | 单 Agent（ReAct + 工具调用）、多 Agent 编排（主管分派 + 反思重写） |
| 长期记忆 | qa_memory 独立 collection 存问答对，问答时自动召回相关历史 |
| 检索评估 | `docs/eval/questions.json`（20 题）+ EvalRunnerTest 命中率评测 |
| 指标观测 | 今日问答量、平均耗时、LLM / 工具调用次数 |
| 数据隔离 | 文档、会话、记忆均按用户隔离；检索支持 documentIds 过滤 |
| 接口文档 | Knife4j 在线 API 文档（http://localhost:18080/doc.html） |

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

**在线问答**（`ChatServiceImpl.ask`）：长期记忆召回 → LLM 查询改写 → 混合检索（稠密 + BM25 稀疏，召回 20）→ Rerank 精排（Top 5）→ 相似度阈值过滤（≥0.35）→ Prompt 拼接 → DeepSeek 生成 → 答案 + 来源引用 → 问答对写入长期记忆

**Agentic 问答**（`AgentController`）：

```
用户提问 → [意图路由 Router]
   ├─ DOCUMENT → RAG 检索问答（原链路）
   ├─ STATS    → Agent 调数据工具（query_document_stats / query_document_list）
   └─ HYBRID   → AgentExecutor 完整 ReAct 循环（≤5 轮）：
                 思考 → 调工具 → 观察结果 → 再思考 → 最终回答
```

> **降级策略**：collection 未重建（无 BM25 字段）时混合检索自动降级为纯稠密；Rerank API 失败时降级按原分数排序；LLM 连续失败 5 次触发熔断 60 秒。

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

# 4. Agentic 问答（ReAct + 工具调用 / 多 Agent 编排）
curl -X POST http://localhost:18080/api/agent/ask \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"目前知识库里有哪些分类的文档？各有多少篇？"}'
curl -X POST http://localhost:18080/api/agent/orchestrate \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"question":"总结知识库内容并生成一份分析报告"}'

# 5. 查看今日指标
curl http://localhost:18080/api/metrics/today -H "Authorization: Bearer $TOKEN"
```

### 检索效果评估

评估集位于 `docs/eval/questions.json`（20 题），通过 Spring Boot 测试运行（需后端环境在线）：

```bash
cd backend
mvn.cmd test -Dtest=EvalRunnerTest
```

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
│       │   │       └── MilvusService.java           向量库 CRUD + 混合检索
│       │   ├── chat/                     💬 问答模块
│       │   │   ├── controller/ChatController.java
│       │   │   ├── entity/ChatSession.java + ChatMessage.java
│       │   │   ├── mapper/ChatSessionMapper.java + ChatMessageMapper.java
│       │   │   └── service/ChatService.java + impl/ChatServiceImpl.java
│       │   │       └── LlmService.java              DeepSeek 调用（chat/chatWithSystem/chatWithTools）
│       │   ├── agent/                    🤖 Agent 模块
│       │   │   ├── Tool.java + ToolRegistry.java    工具抽象 + 注册表
│       │   │   ├── QueryDocumentStatsTool.java      工具：文档统计
│       │   │   ├── QueryDocumentListTool.java       工具：文档列表
│       │   │   ├── GenerateReportTool.java          工具：报告生成
│       │   │   ├── AgentExecutor.java               ReAct 循环执行器（≤5 轮）
│       │   │   ├── Agent.java + DocumentAgent / StatsAgent / ReportAgent
│       │   │   ├── OrchestratorAgent.java           多 Agent 编排（主管分派）
│       │   │   ├── AgentMetrics.java                指标埋点
│       │   │   └── LlmCircuitBreaker.java           熔断器
│       │   ├── rag/                      🔍 检索增强模块
│       │   │   ├── Route.java + RouterService.java + Impl   意图路由
│       │   │   ├── QueryRewriterService.java + Impl         查询改写
│       │   │   ├── RerankService.java + Impl                重排序
│       │   │   ├── CriticService.java + Impl + Critique     反思评审
│       │   │   └── MemoryService.java + Impl                长期记忆（qa_memory）
│       │   ├── controller/               🌐 顶层控制器
│       │   │   ├── AgentController.java             /api/agent/ask + /orchestrate
│       │   │   └── MetricsController.java           /api/metrics/today
│       │   ├── eval/                     📊 评估（EvalRunner.java）
│       │   ├── common/                   🔧 Result / BusinessException / GlobalExceptionHandler / UserContext
│       │   └── config/                   ⚙️ JwtUtil / JwtInterceptor / WebMvcConfig / RestTemplateConfig
│       │       ├── MilvusConfig / MinioConfig / RagProperties(@ConfigurationProperties)
│       │       └── MybatisPlusConfig / MyMetaObjectHandler / CorsConfig
│       ├── main/resources/application.yml
│       └── test/java/com/liushuwen/rag/eval/
│           └── EvalRunnerTest.java       评估测试（读 docs/eval/questions.json）
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
| DELETE | `/api/document/{id}` | 删除文档（级联清理 MinIO/MySQL/Milvus） | 是 |
| POST | `/api/document/embed/{id}` | 触发向量化入库 | 是 |
| POST | `/api/document/rebuild-index` | 重建混合检索索引（升级 BM25 结构，自动回放已向量化文档） | 是 |
| POST | `/api/chat/session` | 创建对话会话 | 是 |
| GET | `/api/chat/sessions` | 会话列表 | 是 |
| PUT | `/api/chat/session/{sessionId}/title` | 修改会话标题 | 是 |
| DELETE | `/api/chat/session/{sessionId}` | 删除会话（级联删除消息） | 是 |
| POST | `/api/chat/ask/{sessionId}` | 智能问答（body：`{"question":"..."}`） | 是 |
| GET | `/api/chat/history/{sessionId}` | 获取会话历史消息 | 是 |
| POST | `/api/agent/ask` | 单 Agent 问答（ReAct + 工具调用） | 是 |
| POST | `/api/agent/orchestrate` | 多 Agent 编排问答（主管分派 + 反思重写） | 是 |
| GET | `/api/metrics/today` | 今日指标（问答量 / 平均耗时 / LLM / 工具调用） | 是 |

> 完整接口文档：http://localhost:18080/doc.html （Knife4j）。认证接口在页面右上角「Authorize」输入 `Bearer <token>` 统一配置。

## 开发里程碑

| 阶段 | 内容 | 状态 |
|------|------|------|
| 第 1-2 周 | 项目搭建、Docker 编排、文档上传解析、MinIO 存储 | ✅ |
| 第 3-4 周 | Embedding 向量化、Milvus 检索、RAG 在线问答 | ✅ |
| 第 5-6 周 | 前端交互、JWT 认证、数据隔离、文档分类 | ✅ |
| 第 7 周 | 项目文档与评估 | ✅ |
| 第 8 周 | Agentic RAG 演进：混合检索 / Rerank / 查询改写 / ReAct / 意图路由 / 多 Agent / 长期记忆 / 降级熔断 / 评估 | ✅ |

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
| BM25 Function | 不支持 | 支持 | 旧 collection 需重建（调用 `POST /api/document/rebuild-index`，自动回放已向量化文档） |

> v1 gRPC 协议向后兼容，不重建 collection 也能继续用（混合检索自动降级为纯稠密）。

### Week6 之前的数据库需要迁移（加 category 列）？

```bash
docker exec -i rag-mysql mysql -uroot -p<你的密码> rag_kb < docker/mysql/migration_week6.sql
```
