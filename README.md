# RAG 智能知识库问答系统

企业级智能知识库问答平台 —— 基于 RAG（检索增强生成）技术，支持文档上传解析、文本分块、向量化入库、语义检索与大模型问答，附带 JWT 认证与多用户数据隔离。

## 功能特性

| 模块 | 功能 |
|------|------|
| 用户认证 | 注册 / 登录（JWT + BCrypt 加密），登录态管理，路由守卫 |
| 文档管理 | 上传（PDF/Word/MD/TXT）、解析分块、向量化入库、分类筛选、删除 |
| 智能问答 | 多会话管理、流式问答、来源引用、Markdown 渲染、历史记录 |
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
| 向量库 | Milvus | 2.4.10 | Docker 容器，端口 19530 |
| 文件存储 | MinIO | 2023.03 | Docker 容器，API 9000 / 控制台 9002 |
| 文档解析 | Apache PDFBox + POI | 3.0.1 / 5.2.5 | PDF / Word / MD / TXT |
| Embedding | 智谱 API (embedding-3) | — | 2048 维向量 |
| 大模型 | DeepSeek API (deepseek-chat) | — | OpenAI 兼容格式 |
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
# 启动全部中间件（MySQL + MinIO + Milvus + etcd）
docker-compose up -d

# 确认容器状态
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

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
│       │   │       ├── LlmService.java                DeepSeek API调用
│       │   │       └── impl/ChatServiceImpl.java      RAG在线流程编排
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
│   ├── 学习笔记.md                       12章+10个面试亮点
│   ├── 面试复习提纲.md                    面试速查卡
│   └── 第3-6周开发任务书.md
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

> 完整接口文档：http://localhost:18080/doc.html
>
> 认证接口需在请求头添加 `Authorization: Bearer <token>`，Knife4j 中可在「Authorize」按钮统一配置。

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

### Q: Knife4j 测试需要认证的接口？

1. 先调用 `/api/auth/register` 注册账号
2. 调用 `/api/auth/login` 登录，复制返回的 `token`
3. 在 Knife4j 页面右上角点击「Authorize」，输入 `Bearer <token>`
