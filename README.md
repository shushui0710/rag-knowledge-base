# RAG 智能知识库问答系统

企业级智能知识库问答平台 —— 基于 RAG（检索增强生成）技术，支持文档自动解析、文本分块、向量化入库与智能问答。

## 系统架构

- **离线文档入库流程**：文档上传 → 解析(PDFBox/POI) → 文本分块(滑动窗口) → Embedding向量化 → Milvus向量库存储
- **在线智能问答流程**：用户提问 → 向量检索Top-K → Prompt拼接 → 大模型生成 → 答案附来源引用

```
                    ┌──────────────┐
    用户上传文档 ───→│  Spring Boot  │───→ MinIO (文件存储)
                    │   后端服务    │───→ MySQL (元数据+分块)
    用户提问       │   :18080      │───→ Milvus (向量检索)  ※第3周启用
                    └──────┬───────┘───→ DeepSeek API (大模型) ※第4周启用
                           │
                    ┌──────┴───────┐
                    │   Vue3 前端   │
                    │   :5173       │
                    └──────────────┘
```

## 技术栈

| 层 | 技术 | 说明 |
|---|---|---|
| 后端框架 | Spring Boot 3.2.5 | Java 17，模块化单体架构 |
| ORM | MyBatis-Plus 3.5.5 | 零代码CRUD + 逻辑删除 + 自动填充 |
| 数据库 | MySQL 8.0 | Docker容器，端口映射 3307→3306 |
| 前端框架 | Vue3 + Element Plus | Vite构建，端口 5173 |
| 向量库 | Milvus 2.4.1 | Docker容器，端口 19530（第3周启用） |
| 文件存储 | MinIO | Docker容器，API端口 9000 / 控制台 9002 |
| 文档解析 | Apache PDFBox 3.0.1 + POI 5.2.5 | PDF/Word/MD/TXT 解析 |
| Embedding | 智谱 API (embedding-3) | 第3周启用 |
| 大模型 | DeepSeek API (deepseek-chat) | 第4周启用 |
| 接口文档 | Knife4j 4.4.0 | http://localhost:18080/doc.html |
| 部署 | Docker Compose | 一键编排所有中间件 |

## 环境要求

在启动项目前，请确保已安装以下软件：

| 软件 | 最低版本 | 验证命令 |
|------|----------|----------|
| JDK | 17 | `java -version` |
| Maven | 3.8+ | `mvn.cmd -version`（Git Bash 中用 `mvn.cmd`） |
| Node.js | 18+ | `node -v` |
| Docker Desktop | 最新版 | `docker --version` |
| Git | 任意 | `git --version` |

> **Windows 用户注意**：在 Git Bash 终端中，Maven 命令需使用 `mvn.cmd` 而非 `mvn`，否则路径格式不兼容会导致报错。

## 快速启动

### 第 0 步：克隆项目

```bash
git clone <仓库地址>
cd rag-knowledge-base
```

### 第 1 步：配置环境变量

项目根目录已有 `.env` 文件（被 `.gitignore` 排除，不会提交到仓库）。首次使用需创建：

```bash
# 在项目根目录创建 .env 文件，内容如下：

# ===== 数据库 =====
MYSQL_ROOT_PASSWORD=rag123456
MYSQL_DATABASE=rag_kb

# ===== MinIO（文档存储） =====
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin

# ===== Milvus 内部 MinIO（不需要改） =====
MILVUS_MINIO_ACCESS_KEY=minioadmin
MILVUS_MINIO_SECRET_KEY=minioadmin

# ===== 大模型 API =====
DEEPSEEK_API_KEY=your-deepseek-api-key
ZHIPU_API_KEY=your-zhipu-api-key
```

> **安全提醒**：`.env` 文件包含敏感信息，已被 `.gitignore` 排除。请勿将 API 密钥硬编码到 `application.yml` 中提交到版本库。

### 第 2 步：启动基础设施（MySQL + MinIO）

```bash
# 在项目根目录执行
docker-compose up -d mysql minio
```

等待约 30 秒，确认两个容器状态为 healthy：

```bash
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
```

预期输出：

```
NAMES        STATUS                   PORTS
rag-minio    Up X minutes (healthy)   0.0.0.0:9000->9000/tcp, 0.0.0.0:9002->9002/tcp
rag-mysql    Up X minutes (healthy)   0.0.0.0:3307->3306/tcp
```

> **端口说明**：MySQL 映射到 3307（避免与本地 MySQL 3306 冲突），MinIO API 在 9000、控制台在 9002。
>
> **Milvus 说明**：Milvus 及其依赖 etcd 因镜像拉取问题暂未启动，第 3 周（向量化入库）开发时再启用。当前阶段不需要 Milvus。

### 第 3 步：启动后端

```bash
cd backend

# 编译（首次运行会下载依赖，约 3-5 分钟）
mvn.cmd compile

# 启动 Spring Boot
mvn.cmd spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

启动成功后，控制台会显示：

```
Started RagKnowledgeBaseApplication in X seconds
```

验证后端是否正常：

```bash
curl http://localhost:18080/api/document/list
# 预期返回: {"code":200,"message":"success","data":[]}
```

接口文档页面：浏览器打开 http://localhost:18080/doc.html

### 第 4 步：启动前端

```bash
cd frontend

# 安装依赖（首次运行，约 1-2 分钟）
npm install

# 启动开发服务器
npm run dev
```

浏览器打开 http://localhost:5173 即可访问前端页面。

## 端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 18080 | REST API + Knife4j 文档 |
| Vue3 前端 | 5173 | Vite 开发服务器 |
| MySQL | 3307 | Docker 映射到容器内 3306 |
| MinIO API | 9000 | S3 兼容接口 |
| MinIO 控制台 | 9002 | Web 管理界面 |
| Milvus | 19530 | 向量数据库（暂未启用） |
| Knife4j 文档 | 18080/doc.html | 接口文档页面 |

## 项目关闭

### 方式一：按端口逐个关闭（推荐，最可靠）

如果后端/前端是在后台运行的（看不到终端窗口），按端口找到 PID 再杀掉：

```bash
# 1. 查看哪些端口还在占用
netstat -ano | findstr "18080 5173"
# 输出类似:  TCP  0.0.0.0:18080  ...  LISTENING  28904
# 最后那列数字就是 PID

# 2. 关闭后端（把 28904 换成你查到的 PID）
taskkill /PID 28904 /F

# 3. 关闭前端（同样换成实际 PID）
taskkill /PID 4228 /F

# 4. 关闭 Docker 容器（MySQL、MinIO 等）
docker-compose down
```

> **提示**：`findstr` 是 Windows 自带命令，在 CMD、PowerShell、Git Bash 中都能用。如果用 Git Bash 也可以写 `netstat -ano | grep "18080"`。

### 方式二：PowerShell 一键关闭

在 PowerShell 中执行（需以管理员身份运行）：

```powershell
# 关闭占用 18080（后端）和 5173（前端）端口的进程
Get-NetTCPConnection -LocalPort 18080,5173 -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }

# 关闭 Docker 容器
docker-compose down
```

### 方式三：彻底清理（含数据）

> **危险操作**：会删除所有数据库数据和上传的文件，不可恢复！

```bash
# 先杀掉后端和前端进程（见方式一）
# 然后停止容器并删除数据卷
docker-compose down -v
```

如果只是下次还要继续开发，用 `docker-compose down`（不加 `-v`）即可，数据会保留在 Docker volume 中。

## 项目结构

项目采用 **模块化单体（Modular Monolith）** 架构，按业务域分包，每个模块自包含全链路代码：

```
rag-knowledge-base/
├── backend/                              Spring Boot 后端
│   ├── pom.xml                           Maven 依赖配置
│   └── src/main/
│       ├── java/com/liushuwen/rag/
│       │   ├── RagKnowledgeBaseApplication.java   启动入口
│       │   │
│       │   ├── document/                 📄 文档管理模块
│       │   │   ├── controller/           DocumentController（上传/列表/删除/向量化）
│       │   │   ├── entity/               Document + DocumentChunk 实体
│       │   │   ├── mapper/               DocumentMapper + DocumentChunkMapper
│       │   │   └── service/              DocumentService + MinioService
│       │   │       └── impl/             DocumentServiceImpl（核心业务逻辑）
│       │   │
│       │   ├── chat/                     💬 智能问答模块（第4-5周开发）
│       │   │   ├── controller/           ChatController
│       │   │   ├── entity/               ChatSession + ChatMessage
│       │   │   ├── mapper/               ChatSessionMapper + ChatMessageMapper
│       │   │   └── service/              ChatService
│       │   │
│       │   ├── auth/                     🔐 认证模块（第6周开发）
│       │   │   ├── controller/           AuthController
│       │   │   ├── entity/               User
│       │   │   ├── mapper/               UserMapper
│       │   │   └── service/              UserService
│       │   │
│       │   ├── common/                   🔧 通用组件
│       │   │   ├── Result.java           统一响应封装 {code, message, data}
│       │   │   ├── BusinessException.java 业务异常类
│       │   │   └── GlobalExceptionHandler.java 全局异常处理器
│       │   │
│       │   └── config/                   ⚙️ 配置类
│       │       ├── MinioConfig.java      MinIO 客户端配置
│       │       ├── MybatisPlusConfig.java 分页插件配置
│       │       ├── MyMetaObjectHandler.java 自动填充时间字段
│       │       └── CorsConfig.java       跨域配置
│       │
│       └── resources/
│           └── application.yml           后端核心配置
│
├── frontend/                             Vue3 前端
│   ├── package.json                      依赖配置
│   ├── vite.config.js                    Vite 配置（含 API 代理）
│   └── src/
│       ├── main.js                       入口
│       ├── App.vue                       根组件
│       ├── router/index.js               路由配置
│       ├── api/                          API 请求封装
│       │   ├── index.js                  Axios 实例
│       │   ├── document.js               文档相关 API
│       │   └── chat.js                   对话相关 API
│       └── views/                        页面
│           ├── DocumentView.vue          文档管理页
│           └── ChatView.vue              智能问答页
│
├── docker-compose.yml                    Docker 编排配置
├── docker/mysql/init/init.sql            MySQL 建表脚本（自动执行）
├── .env                                  环境变量（不入库）
├── .gitignore                            Git 忽略规则
└── README.md                             本文档
```

## 数据库表结构

MySQL 启动时自动执行 `docker/mysql/init/init.sql`，创建以下表：

| 表名 | 说明 |
|------|------|
| `user` | 用户表（用户名/密码/昵称/邮箱） |
| `document` | 文档表（标题/文件名/类型/大小/MinIO路径/分块数/向量化状态） |
| `document_chunk` | 文档分块表（文档ID/序号/内容/字符数） |
| `chat_session` | 对话会话表（用户ID/标题） |
| `chat_message` | 对话消息表（会话ID/角色/内容/来源引用） |

测试用户：`admin` / `admin123`

## API 接口

当前已实现的接口：

| 方法 | 路径 | 说明 | 状态 |
|------|------|------|------|
| POST | `/api/document/upload` | 上传文档（MinIO存储 + 解析分块 + MySQL入库） | ✅ 已实现 |
| GET | `/api/document/list` | 获取文档列表 | ✅ 已实现 |
| DELETE | `/api/document/{id}` | 删除文档 | ✅ 已实现 |
| POST | `/api/document/embed/{id}` | 触发文档向量化入库 | 🔜 第3周 |
| POST | `/api/chat/ask` | 智能问答 | 🔜 第4周 |

> 完整接口文档：启动后端后访问 http://localhost:18080/doc.html

## 开发计划与进度

| 周 | 内容 | 状态 |
|---|---|---|
| 第1周 | 项目搭建 + 环境配置 + Docker 编排 | ✅ 已完成 |
| 第2周 | 文档上传 + 解析分块 + MinIO存储 | ✅ 已完成 |
| 第3周 | RAG核心 - Embedding向量化 + Milvus检索 | 🔜 待开发 |
| 第4周 | RAG核心 - 大模型生成 + 流式输出 | 🔜 待开发 |
| 第5周 | 前端对话交互页面 | 🔜 待开发 |
| 第6周 | 系统完善（认证/分类/历史） | 🔜 待开发 |
| 第7周 | 文档与交付 | 🔜 待开发 |

## 常见问题

### Q: Docker 拉取镜像超时怎么办？

国内网络环境下 Docker Hub 可能无法直接访问。请在 Docker Desktop 设置中配置镜像加速器（Settings → Docker Engine）：

```json
{
  "registry-mirrors": [
    "https://docker.m.daocloud.io",
    "https://docker.1panel.live"
  ]
}
```

修改后点击 Apply & Restart。

### Q: Maven 下载依赖很慢怎么办？

在 `~/.m2/settings.xml` 中配置阿里云镜像：

```xml
<mirror>
    <id>aliyunmaven</id>
    <mirrorOf>*</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
</mirror>
```

### Q: 端口被占用怎么办？

| 端口 | 对应服务 | 修改方式 |
|------|----------|----------|
| 18080 | Spring Boot | `application.yml` 中 `server.port` |
| 5173 | Vue3 前端 | `vite.config.js` 中 `server.port` |
| 3307 | MySQL | `docker-compose.yml` 中端口映射 |
| 9000/9002 | MinIO | `docker-compose.yml` 中端口映射 |

### Q: Git Bash 中 mvn 命令报错？

在 Git Bash 中使用 `mvn.cmd` 代替 `mvn`。这是 Windows 路径格式兼容问题，`mvn`（Unix脚本）传给 Windows Java 的路径格式不被识别。

### Q: 后端启动后 SLF4J 警告怎么办？

控制台出现 `SLF4J: Failed to instantiate provider reload4j...` 警告可以忽略，不影响运行。
