# RAG 智能知识库问答系统

企业级智能知识库问答平台 - 基于RAG（检索增强生成）技术，支持文档自动解析、向量化入库与智能问答。

## 系统架构

- **离线文档入库流程**：文档上传 → 解析(PDFBox/POI) → 文本分块 → Embedding向量化 → Milvus向量库存储
- **在线智能问答流程**：用户提问 → 向量检索Top-K → Prompt拼接 → 大模型生成 → 答案附来源引用

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3 + MyBatis-Plus + MySQL 8.0 |
| 前端 | Vue3 + Element Plus |
| 向量库 | Milvus 2.4 |
| 文件存储 | MinIO |
| Embedding | 智谱API (bge-large-zh) |
| 大模型 | DeepSeek API |
| 文档解析 | Apache PDFBox + POI |
| 部署 | Docker Compose |

## 快速启动

### 1. 启动基础设施

```bash
cd rag-knowledge-base
docker-compose up -d
```

等待MySQL、Milvus、MinIO全部启动完成（约1-2分钟）。

### 2. 启动后端

```bash
cd backend
mvn spring-boot:run
```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 http://localhost:5173

### 4. 配置API密钥

在 `backend/src/main/resources/application.yml` 中配置：

- `ZHIPU_API_KEY`：智谱Embedding API密钥
- `DEEPSEEK_API_KEY`：DeepSeek大模型API密钥

或通过环境变量设置：

```bash
export ZHIPU_API_KEY=your-key
export DEEPSEEK_API_KEY=your-key
```

## 开发计划

| 周 | 内容 |
|---|---|
| 第1周 | 项目搭建 + 环境配置 |
| 第2周 | 文档管理 + 解析入库基础 |
| 第3周 | RAG核心 - 向量化与检索 |
| 第4周 | RAG核心 - 大模型生成 + 流式输出 |
| 第5周 | 前端对话交互 |
| 第6周 | 系统完善（认证/分类/历史） |
| 第7周 | 文档与交付 |

## 项目结构

```
rag-knowledge-base/
├── backend/          Spring Boot后端
│   ├── pom.xml
│   └── src/main/java/com/liushuwen/rag/
│       ├── config/           配置类
│       ├── common/           通用类(Result/异常处理)
│       ├── entity/           实体类
│       ├── mapper/           MyBatis-Plus Mapper
│       ├── controller/       控制器
│       ├── service/          服务层
│       └── service/impl/     服务实现
│   └── src/main/resources/
│       └── application.yml   配置文件
├── frontend/         Vue3前端
│   ├── package.json
│   ├── vite.config.js
│   └── src/
│       ├── router/           路由
│       ├── views/            页面
│       ├── api/              API请求
│       ├── components/       组件
│       └── stores/           Pinia状态管理
├── docker-compose.yml        Docker部署
├── docker/mysql/init/        MySQL初始化脚本
└── docs/                     文档
```
