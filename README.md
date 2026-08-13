# RAG 智能知识库问答系统

企业级智能知识库问答平台 —— 基于 RAG（检索增强生成）技术，支持文档上传解析、文本分块、向量化入库、语义检索与大模型问答，附带 JWT 认证与多用户数据隔离。

已从"普通 RAG"演进为 **Agentic RAG**：通过 Function Calling 工具调用、ReAct 循环与意图路由，让助手能自主决定"查文档 / 查数据库 / 生成报告"；检索侧升级 **Milvus 2.5 内置 BM25 Function** 实现稠密 + 稀疏双路混合检索。

## 功能特性

| 模块 | 功能 |
|------|------|
| 用户认证 | 注册 / 登录（JWT + BCrypt 加密），登录态管理，路由守卫 |
| 文档管理 | 上传（PDF/Word/MD/TXT）、解析分块、向量化入库、分类筛选、删除、增量重传（TODO） |
| 智能问答 | 多会话管理、流式问答、来源引用、Markdown 渲染、历史记录 |
| **Agent 智能问答** | `POST /api/agent/ask`：Function Calling + ReAct 循环，多工具自主调度（工具调用/检索/路由，骨架已就绪） |
| **多 Agent 编排** | `POST /api/agent/orchestrate`：主管 Agent 路由分派 + HYBRID 组合回答 + 反思重写（✅ 已实现） |
| **长期记忆 / 反思** | qa_memory 独立 collection 存问答对 + LLM 评审重写（✅ 已实现） |
| **检索评估** | `docs/eval/questions.json` + EvalRunnerTest 命中率评测（✅ 已实现） |
| **混合检索** | Milvus 2.5 BM25 Function（路线A）：稠密 + 稀疏双路召回 + 加权融合（✅ 已实现） |
| **Rerank / 查询改写** | 智谱 rerank 精排（召回20→精排5）+ LLM 查询改写（✅ 已实现） |
| **指标观测** | `GET /api/metrics/today`：问答次数、平均耗时、LLM/工具调用次数 |
| 数据隔离 | 文档与会话按用户隔离，用户只能看到自己的数据 |
| 接口文档 | Knife4j 在线 API 文档，支持在线调试 |

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
│  ┌─────────┐  ┌──────────────┐  ┌────────────┐  ┌──────────────────┐  │
│  │  auth   │  │  document    │  │   chat     │  │     config       │  │
│  │ 认证模块 │  │  文档模块    │  │  问答模块  │  │  JWT/跨域/拦截器  │  │
│  └─────────┘  └──────┬───────┘  └─────┬──────┘  └──────────────────┘  │
│                      │                │                                 │
│     JwtInterceptor ──┴── UserContext ──┘  (ThreadLocal 持有当前用户)     │
└──────────────────────┬──────────────────────┬───────────────────────────┘
                       │                      │
           ┌───────────┼───────────┐          │
           ▼           ▼           ▼          ▼
      ┌────────┐ ┌────────┐ ┌─────────┐ ┌───────────┐
      │ MinIO  │ │ MySQL  │ │ Milvus  │ │DeepSeek API│
      │ 文件存储 │ │元数据+ │ │向量检索  │ │ + 智谱API  │
      │ :9000   │ │分块+会话│ │ :19530  │ │ (Embedding)│
      └────────┘ └────────┘ └─────────┘ └───────────┘
```

### 离线流程（文档入库）

文档上传 → MinIO 存储文件 → PDFBox/POI 解析 → 滑动窗口分块 → 智谱 Embedding 向量化 → Milvus 存储

### 在线流程（智能问答）

用户提问 → 智谱 Embedding 向量化 → Milvus 语义检索 Top-K → Prompt 拼接 → DeepSeek 大模型生成 → 答案 + 来源引用

### Agentic 问答流程（阶段 3，骨架已就绪）

```
用户提问 → [意图路由 Router] → 判断走哪条链路
   ├─ DOCUMENT → RAG 检索问答（原链路）
   ├─ STATS    → Agent 调数据工具（query_document_stats / query_document_list）
   └─ HYBRID   → AgentExecutor 完整 ReAct 循环：
                  思考 → 调工具 → 观察结果 → 再思考 → 最终回答（最多 5 轮）
```

### 混合检索流程（TODO 2-1，路线 A）

```
查询改写 → 双路召回 → 分数融合 → Rerank（TODO 2-2）→ Top5 → Prompt
          ├─ 稠密路：embedding 向量（Milvus FloatVector）
          └─ 稀疏路：BM25 Function 自动生成稀疏向量（Milvus 2.5+，SparseFloatVector）
```

## 技术栈

| 层 | 技术 | 版本 | 说明 |
|----|------|------|------|
| 后端框架 | Spring Boot | 3.2.5 | Java 17，模块化单体架构 |
| ORM | MyBatis-Plus | 3.5.5 | 零代码 CRUD + 逻辑删除 + 自动填充 |
| 认证 | JJWT + spring-security-crypto | 0.12.6 / 6.3.0 | JWT 签发解析 + BCrypt 密码加密 |
| 数据库 | MySQL | 8.0 | Docker 容器，端口 3307→3306 |
| 前端框架 | Vue3 + Element Plus | 3.4 / 2.7 | Vite 构建，Composition API |
| 前端状态 | Pinia | 2.1 | 替代 Vuex，更轻量 |
| 前端路由 | Vue Router | 4.3 | 含路由守卫 |
| 向量库 | Milvus | 2.5.16 | Docker 容器，端口 19530；启用内置 BM25 Function（路线A） |
| Milvus SDK | milvus-sdk-java | 2.5.14 | v1 API（MilvusServiceClient）继续使用；SDK 2.5 起不再传递 fastjson，需显式依赖 |
| JSON | fastjson | 1.2.83 | 显式声明（原由 Milvus SDK 2.4.1 传递） |
| 文件存储 | MinIO | 2023.03 | Docker 容器，API 9000 / 控制台 9002 |
| 文档解析 | Apache PDFBox + POI | 3.0.1 / 5.2.5 | PDF / Word / MD / TXT |
| Embedding | 智谱 API (embedding-3) | — | 2048 维向量 |
| 大模型 | DeepSeek API (deepseek-v4-flash) | — | OpenAI 兼容格式（deepseek-chat 已于 2026-07-24 弃用） |
| 接口文档 | Knife4j | 4.4.0 | http://localhost:18080/doc.html |
| 部署 | Docker Compose | — | 一键编排所有中间件 |

## 环境要求

| 软件 | 最低版本 | 验证命令 |
|------|----------|----------|
| JDK | 17 | `java -version` |
| Maven | 3.8+ | `mvn.cmd -version`（Git Bash 中用 `mvn.cmd`） |
| Node.js | 18+ | `node -v` |
| Docker Desktop | 最新版 | `docker --version` |
| Git | 任意 | `git --version` |

> **Windows 用户**：Git Bash 中 Maven 命令需用 `mvn.cmd` 而非 `mvn`，否则路径格式不兼容。

## 快速启动

### 第 0 步：克隆项目

```bash
git clone <仓库地址>
cd rag-knowledge-base
```

### 第 1 步：配置环境变量

复制 `.env.example` 为 `.env`，填入 API 密钥：

```bash
cp .env.example .env
```

```env
# 数据库
MYSQL_ROOT_PASSWORD=rag123456
MYSQL_DATABASE=rag_kb

# MinIO
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin

# Milvus 内部 MinIO（默认即可）
MILVUS_MINIO_ACCESS_KEY=minioadmin
MILVUS_MINIO_SECRET_KEY=minioadmin

# 大模型 API（必填）
DEEPSEEK_API_KEY=your-deepseek-api-key
ZHIPU_API_KEY=your-zhipu-api-key

# JWT（开发环境可用默认值，生产环境务必修改）
JWT_SECRET=your-random-secret
```

> API 密钥获取：[DeepSeek](https://platform.deepseek.com) / [智谱](https://open.bigmodel.cn)

### 第 2 步：启动基础设施

```bash
# 启动全部中间件（MySQL + MinIO + Milvus 2.5.16 + etcd）
docker-compose up -d

# 确认容器状态
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

> ⚠️ **从 Milvus 2.4 升级**：docker-compose.yml 已更新为 `milvusdb/milvus:v2.5.16`，需重新拉取镜像：
> ```bash
> docker-compose down
> docker-compose up -d   # 会拉取 v2.5.16 新镜像
> ```
> 旧 collection 数据可继续使用（v1 gRPC 协议向后兼容）；但要启用 BM25 Function 混合检索（TODO 2-1），需**重建 collection**（新增 `bm25_vector` 稀疏字段 + BM25 Function）。

预期输出 5 个容器均为 healthy：

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

```bash
cd backend
mvn.cmd spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

启动成功后控制台显示 `Started RagKnowledgeBaseApplication`。

接口文档：浏览器打开 http://localhost:18080/doc.html

### 第 4 步：启动前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 http://localhost:5173，首次访问会跳转到登录页，点击「注册」创建账号即可开始使用。

## 端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 18080 | REST API + Knife4j 文档 |
| Vue3 前端 | 5173 | Vite 开发服务器 |
| MySQL | 3307 | 映射到容器内 3306 |
| MinIO API | 9000 | S3 兼容接口 |
| MinIO 控制台 | 9002 | Web 管理界面 |
| Milvus | 19530 | 向量数据库 |
| Milvus 健康检查 | 9091 | /healthz |
| Knife4j 文档 | 18080/doc.html | 接口文档页面 |

## 项目结构

模块化单体（Modular Monolith）架构，按业务域分包：

```
rag-knowledge-base/
├── backend/                              Spring Boot 后端
│   ├── pom.xml                           Maven 依赖
│   └── src/main/
│       ├── java/com/liushuwen/rag/
│       │   ├── RagKnowledgeBaseApplication.java   启动入口
│       │   │
│       │   ├── auth/                     🔐 认证模块
│       │   │   ├── controller/AuthController.java    注册/登录/获取当前用户
│       │   │   ├── entity/User.java                  用户实体
│       │   │   ├── mapper/UserMapper.java
│       │   │   └── service/UserService.java
│       │   │       └── impl/UserServiceImpl.java     BCrypt加密+JWT
│       │   │
│       │   ├── document/                 📄 文档管理模块
│       │   │   ├── controller/DocumentController.java
│       │   │   ├── entity/Document.java              +category字段
│       │   │   ├── entity/DocumentChunk.java
│       │   │   ├── mapper/DocumentMapper.java
│       │   │   ├── mapper/DocumentChunkMapper.java
│       │   │   └── service/
│       │   │       ├── DocumentService.java
│       │   │       ├── MinioService.java             MinIO文件操作
│       │   │       ├── DocumentParserService.java    PDF/Word/MD/TXT解析
│       │   │       ├── DocumentChunkService.java      滑动窗口分块
│       │   │       ├── EmbeddingService.java          智谱API向量化
│       │   │       ├── MilvusService.java             向量库CRUD+检索
│       │   │       └── impl/DocumentServiceImpl.java  编排全流程
│       │   │
│       │   ├── chat/                    💬 智能问答模块
│       │   │   ├── controller/ChatController.java
│       │   │   ├── entity/ChatSession.java
│       │   │   ├── entity/ChatMessage.java
│       │   │   ├── mapper/ChatSessionMapper.java
│       │   │   ├── mapper/ChatMessageMapper.java
│       │   │   └── service/
│       │   │       ├── ChatService.java
│       │   │       ├── LlmService.java                DeepSeek API调用（chat/chatWithSystem/chatWithTools）
│       │   │       └── impl/ChatServiceImpl.java      RAG在线流程编排
│       │   │
│       │   ├── agent/                   🤖 Agent 模块（第8周新增）
│       │   │   ├── Tool.java / ToolRegistry.java      工具抽象 + 注册表
│       │   │   ├── QueryDocumentStatsTool.java        ✅ 工具1：文档统计（已实现）
│       │   │   ├── QueryDocumentListTool.java         ✅ 工具2：文档列表（已实现）
│       │   │   ├── GenerateReportTool.java            ✅ 工具3：报告生成（已实现）
│       │   │   ├── AgentExecutor.java                 ✅ ReAct 循环执行器（已实现）
│       │   │   ├── Agent.java + Document/Stats/ReportAgent + OrchestratorAgent ✅ 多Agent 编排（含反思）
│       │   │   ├── AgentMetrics.java                  ✅ 指标埋点（已实现）
│       │   │   └── LlmCircuitBreaker.java             ✅ 熔断器（已实现）
│       │   │
│       │   ├── rag/                     🔍 检索增强（第8周新增）
│       │   │   ├── Route.java / RouterService.java    ✅ 意图路由（已实现）
│       │   │   ├── QueryRewriterService.java          查询改写（✅ 已实现）
│       │   │   ├── RerankService.java                 重排序（✅ 已实现）
│       │   │   ├── MemoryService.java                 ✅ 长期记忆（qa_memory collection）
│       │   │   └── CriticService.java                 反思评审（TODO 4-3）
│       │   │
│       │   ├── controller/               🌐 控制器
│       │   │   ├── AgentController.java               /api/agent/ask + /orchestrate
│       │   │   └── MetricsController.java             /api/metrics/today
│       │   │
│       │   ├── eval/                     📊 评估（第8周新增）
│       │   │   └── EvalRunner.java                    ✅ 评估（test 目录 EvalRunnerTest + docs/eval/questions.json）
│       │   │
│       │   ├── common/                  🔧 通用组件
│       │   │   ├── Result.java                        统一响应封装
│       │   │   ├── BusinessException.java             业务异常
│       │   │   ├── GlobalExceptionHandler.java        全局异常处理
│       │   │   └── UserContext.java                   ThreadLocal持有userId
│       │   │
│       │   └── config/                   ⚙️ 配置类
│       │       ├── JwtUtil.java                        JWT生成/解析/验证
│       │       ├── JwtInterceptor.java                请求拦截+身份提取
│       │       ├── WebMvcConfig.java                  注册拦截器+排除路径
│       │       ├── RestTemplateConfig.java            HTTP客户端@Bean
│       │       ├── MilvusConfig.java                  Milvus客户端配置
│       │       ├── MinioConfig.java                   MinIO客户端配置
│       │       ├── RagProperties.java                 RAG/Agent配置组（@ConfigurationProperties）
│       │       ├── MybatisPlusConfig.java             分页插件
│       │       ├── MyMetaObjectHandler.java           自动填充时间
│       │       └── CorsConfig.java                    跨域配置
│       │
│       └── resources/
│           └── application.yml           后端配置
│
├── frontend/                             Vue3 前端
│   ├── package.json
│   ├── vite.config.js                    API代理→localhost:18080
│   └── src/
│       ├── main.js
│       ├── App.vue                       导航+用户信息+退出
│       ├── router/index.js               路由+登录守卫
│       ├── api/
│       │   ├── index.js                   Axios实例+token拦截器
│       │   ├── auth.js                    注册/登录/获取用户
│       │   ├── document.js               上传/列表/删除/向量化
│       │   └── chat.js                   会话/问答/历史
│       ├── stores/
│       │   ├── auth.js                   token+用户状态
│       │   └── chat.js                   会话列表状态
│       └── views/
│           ├── LoginView.vue             登录/注册页
│           ├── DocumentView.vue          文档管理+分类
│           └── ChatView.vue              对话页+Markdown渲染
│
├── docker/
│   └── mysql/
│       ├── init/init.sql                 建表脚本（自动执行）
│       └── migration_week6.sql          已有库迁移脚本
│
├── docs/
│   ├── README.md                          文档导航（按场景选文档）
│   ├── 学习笔记.md                       13章+11个面试亮点
│   ├── 面试复习提纲.md                    面试速查卡
│   ├── 技术亮点与架构设计.md              架构图+选型理由+11亮点（面试规范版）
│   ├── AgenticRAG面试速记卡.md            面试前30分钟速记（链路图+10话术+手撕清单）
│   ├── 测试报告与验收结论.md              验收结论+降级矩阵+边界防御
│   ├── AgenticRAG演进辅导方案.md          第8周：5阶段演进方案（含【思路提示】+【标准答案】+📁目录）
│   ├── AgenticRAG骨架落地说明.md          骨架文件清单/TODO分布/里程碑验证
│   ├── AgenticRAG辅导方案逐项审查报告.md   全部TODO事实核查（已验证/待确认分级）
│   ├── 第3-6周开发任务书.md
│   └── eval/questions.json                检索评估集（5题示例，可扩展20题）
│
├── docker-compose.yml                    Docker编排
├── .env.example                          环境变量模板
├── .gitignore
└── README.md                             本文档
```

## 数据库表结构

MySQL 启动时自动执行 `docker/mysql/init/init.sql`：

| 表名 | 说明 |
|------|------|
| `user` | 用户表（用户名/BCrypt密码/昵称/邮箱/创建时间） |
| `document` | 文档表（标题/文件名/类型/大小/MinIO路径/分块数/向量化状态/分类/userId） |
| `document_chunk` | 分块表（文档ID/序号/内容/字符数） |
| `chat_session` | 会话表（userId/标题/创建时间/更新时间） |
| `chat_message` | 消息表（会话ID/角色/内容/来源引用JSON） |

> 密码使用 BCrypt 加密存储，无明文测试用户，需通过注册接口创建账号。

## API 接口

| 方法 | 路径 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/auth/register` | 用户注册 | 否 |
| POST | `/api/auth/login` | 用户登录（返回 JWT） | 否 |
| GET | `/api/auth/me` | 获取当前用户信息 | 是 |
| POST | `/api/document/upload` | 上传文档（MinIO + 解析分块 + 入库） | 是 |
| GET | `/api/document/list` | 文档列表（按用户+分类过滤） | 是 |
| DELETE | `/api/document/{id}` | 删除文档 | 是 |
| POST | `/api/document/embed/{id}` | 触发向量化入库 | 是 |
| POST | `/api/chat/session` | 创建对话会话 | 是 |
| GET | `/api/chat/sessions` | 会话列表 | 是 |
| DELETE | `/api/chat/session/{id}` | 删除会话（级联删除消息） | 是 |
| POST | `/api/chat/ask` | 智能问答 | 是 |
| GET | `/api/chat/history/{sessionId}` | 获取对话历史 | 是 |
| POST | `/api/agent/ask` | Agent 单轮问答（ReAct + 工具调用，骨架直通 LLM） | 是 |
| POST | `/api/agent/orchestrate` | 多 Agent 编排问答（主管分派） | 是 |
| GET | `/api/metrics/today` | 今日 Agent 指标（问答数/耗时/LLM/工具调用） | 是 |

> 完整接口文档：http://localhost:18080/doc.html
>
> 认证接口需在请求头添加 `Authorization: Bearer <token>`，Knife4j 中可在「Authorize」按钮统一配置。
>
> ⚠️ Agent 接口当前为骨架直通模式（TODO 3-2/3-3 完成后才真正调用工具），详见 `docs/AgenticRAG骨架落地说明.md`。

## 开发进度

| 周 | 内容 | 状态 |
|----|------|------|
| 第1周 | 项目搭建 + 环境配置 + Docker 编排 | ✅ |
| 第2周 | 文档上传 + 解析分块 + MinIO 存储 | ✅ |
| 第3周 | Embedding 向量化 + Milvus 检索 | ✅ |
| 第4周 | 大模型生成 + RAG 在线流程 | ✅ |
| 第5周 | 前端对话交互（Markdown/路由/Pinia） | ✅ |
| 第6周 | JWT 认证 + 数据隔离 + 文档分类 | ✅ |
| 第7周 | 项目文档 + 面试复习材料 | ✅ |
| 第8周 | Agentic RAG 演进 5 阶段全部完成（检索优化/Agentic核心/多Agent/记忆反思/评估缓存） | ✅ |

### 第 8 周演进 TODO 清单（骨架已落地，标准答案见文档）

| 优先级 | TODO | 状态 |
|--------|------|------|
| ⭐ | 1-1 增量更新 / 1-1b 删向量 | 🔄 已实现（reparseDocument+deleteByDocumentId） |
| ⭐⭐ | 2-1 混合检索（路线A：Milvus 2.5 BM25 Function） | ✅ 已实现 |
| ⭐⭐ | 2-2 Rerank / 2-3 查询改写 | ✅ 已实现 |
| ⭐⭐⭐ | 3-2 Function Calling / 3-3 ReAct 循环 / 3-4 意图路由 | ✅ 已实现 |
| ⭐⭐⭐ | 4-1 多 Agent / 4-2 长期记忆 / 4-3 反思 | ✅ 已实现 |
| ⭐⭐ | 5-1 评估集 / 5-2 观测 / 5-3 缓存 / 5-4 容错 | ✅ 已实现 |

## 常见问题

### Q: Docker 拉取镜像超时？

在 Docker Desktop 设置中配置镜像加速器（Settings → Docker Engine）：

```json
{
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://docker.mirrors.163.com"
  ]
}
```

> quay.io 镜像（etcd）如拉取慢，可在 Git Bash 中设置 `DOCKER_CONFIG=/tmp/docker-config docker pull`。

### Q: Maven 下载依赖很慢？

在 `~/.m2/settings.xml` 配置阿里云镜像：

```xml
<mirror>
    <id>aliyunmaven</id>
    <mirrorOf>*</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

### Q: 端口被占用？

| 端口 | 服务 | 修改位置 |
|------|------|----------|
| 18080 | Spring Boot | `application.yml` → `server.port` |
| 5173 | Vue3 前端 | `vite.config.js` → `server.port` |
| 3307 | MySQL | `docker-compose.yml` 端口映射 |
| 9000/9002 | MinIO | `docker-compose.yml` 端口映射 |
| 19530 | Milvus | `docker-compose.yml` 端口映射 |

### Q: Git Bash 中 mvn 报错？

Git Bash 中使用 `mvn.cmd` 代替 `mvn`，这是 Windows 路径格式兼容问题。

### Q: 已有数据库需要升级（加 category 列）？

如果数据库在 Week 6 之前创建，需执行迁移脚本：

```bash
docker exec -i rag-mysql mysql -uroot -prag123456 rag_kb < docker/mysql/migration_week6.sql
```

### Q: Milvus 从 2.4 升级到 2.5（本次路线 A 改动）？

| 项 | 2.4（旧） | 2.5（新） | 需要改什么 |
|----|-----------|-----------|-----------|
| 服务端镜像 | `milvusdb/milvus:v2.4.10` | `v2.5.16` | `docker-compose.yml` 已更新，`docker-compose up -d` 重拉镜像 |
| Java SDK | `milvus-sdk-java 2.4.1` | `2.5.14` | `pom.xml` 已更新 |
| JSON 库 | SDK 传递 fastjson | SDK 改用 **Gson** | ① `pom.xml` 显式声明 fastjson 1.2.83（项目代码直接用）② `MilvusService.insertVectors` 已从 fastjson 改 Gson `JsonObject` |
| v1 API | — | 兼容保留 | `MilvusServiceClient` / `DeleteParam` / `SearchParam` 均无需改动（已编译验证） |
| BM25 Function | 不支持 | 支持 | TODO 2-1：重建 collection，新增 `bm25_vector`（SparseFloatVector）+ `FunctionType.BM25`，详见 `MilvusService.hybridSearch` 注释 |

> 旧 collection 数据不迁移也能继续查询（协议向后兼容）；启用混合检索才需要重建 collection。

### Q: Knife4j 测试需要认证的接口？

1. 先调用 `/api/auth/register` 注册账号
2. 调用 `/api/auth/login` 登录，复制返回的 `token`
3. 在 Knife4j 页面右上角点击「Authorize」，输入 `Bearer <token>`
