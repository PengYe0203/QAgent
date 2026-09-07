# QAgent — 企业文档 RAG 智能问答服务

基于 **Spring Boot 3.5 + Spring AI 1.1 + PostgreSQL/pgvector** 的文档问答服务：上传 PDF / Word / Excel 文档后自动切片入库，通过「向量检索 + 关键词检索」混合召回 + Rerank 重排 + DeepSeek 大模型生成带出处引用的回答。

## 功能特性

- 📄 文档解析与入库：支持 **PDF / Word / Excel**，自动分块并向量化存入 pgvector
- 🔎 混合检索：**向量相似度（BGE-M3）+ HanLP 中文分词关键词检索**，缓解中文长尾词召回不足
- ⚖️ Rerank 重排：SiliconFlow `bge-reranker-v2-m3` 对召回结果精排，提升 Top-K 命中率
- 💬 多轮对话：服务端维护会话历史（会话 ID），支持追问与指代消解
- ⚡ 流式回答：SSE 逐 token 输出，打字机效果
- 📋 回答带出处：返回命中文档片段（`sources`），可直接核对来源
- 🌐 内置网页端：`src/main/resources/static/index.html`，开箱即用

## 技术栈

| 模块 | 选型 |
| --- | --- |
| 框架 | Spring Boot 3.5.3 / Spring AI 1.1.8 |
| 语言 | Java 17 |
| 对话模型 | DeepSeek（`deepseek-chat`） |
| Embedding | SiliconFlow `BAAI/bge-m3`（OpenAI 兼容接口，1024 维） |
| Rerank | SiliconFlow `BAAI/bge-reranker-v2-m3` |
| 向量库 | PostgreSQL 16 + pgvector（HNSW / COSINE_DISTANCE） |
| 中文分词 | HanLP portable |
| 文档解析 | Apache PDFBox / Apache POI |

## 架构流程

```
上传文档(PDF/Word/Excel)
   └─> DocumentParserFactory ─ 解析文本
         └─> TextChunker ─ 分块(带重叠)
               └─> Embedding(BGE-M3) ─> 写入 pgvector(vector_store 表)

提问 ─> 向量检索(pgvector) ─┬─> 混合排序 / 融合
     └> 关键词检索(HanLP+trgm) ┘
              └─> Rerank 精排 ─> 取 Top-K 片段
                    └─> DeepSeek 生成回答(带引用) ─> 用户 / SSE
```

## 目录结构

```
src/main/java/com/company/qagent/
├── controller/    # REST 接口（问答 / 文档管理）
├── service/       # RAG、混合检索、文档入库、会话存储
├── parser/        # PDF / Word / Excel 解析
├── chunker/       # 文本分块
├── rerank/        # Rerank 客户端
├── config/        # pgvector 初始化
└── model/         # DTO
src/main/resources/
├── application.properties         # 公共配置（密钥走环境变量）
├── application-local.properties   # 本地密钥配置（已 gitignore，需自建，见下）
└── static/index.html              # 内置网页
```

## 快速开始

### 前置要求

- JDK 17、Maven 3.8+
- Docker（用于启动 pgvector 数据库）
- DeepSeek API Key、SiliconFlow API Key

### 1. 启动数据库

```bash
docker compose up -d
```

启动 PostgreSQL 16 + pgvector 容器（默认 `postgres/postgres`，库名 `qagent`，端口 5432）。

> 注意：`docker-compose.yml` 中数据库密码为 `postgres`，与本机配置默认值 `root` 不一致，启动应用前请通过环境变量或修改本地配置对齐（见下）。

### 2. 创建本地密钥配置（已被 .gitignore 排除，不会上传）

```bash
cp src/main/resources/application-local.properties.example src/main/resources/application-local.properties
```

仓库不包含该文件，请参照 `src/main/resources/application-local.properties.example` 手动创建 `src/main/resources/application-local.properties`，填入你的密钥：

```properties
# SiliconFlow（Embedding + Rerank 共用）
spring.ai.openai.api-key=${EMBEDDING_API_KEY:你的_SiliconFlow_Key}
app.rerank.api-key=${RERANK_API_KEY:你的_SiliconFlow_Key}

# 数据库连接（与 docker-compose.yml 对齐）
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:qagent}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:postgres}

# DeepSeek
spring.ai.deepseek.api-key=${DEEPSEEK_API_KEY:你的_DeepSeek_Key}

# pgvector
spring.ai.vectorstore.pgvector.initialize-schema=true
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.dimensions=1024   # BGE-M3 为 1024 维
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

浏览器打开 <http://localhost:8080> 即可使用内置网页端。

所有密钥均可通过环境变量覆盖（`DEEPSEEK_API_KEY`、`EMBEDDING_API_KEY`、`RERANK_API_KEY`、`DB_HOST`、`DB_PASSWORD` 等），便于部署到服务器 / CI。

## API 一览

### 问答 `POST /api/chat`

```json
{ "question": "报销流程是什么？", "conversationId": "可选，留空则新建会话" }
```

```json
{
  "answer": "……（回答正文）",
  "sources": [ { "file": "财务制度.docx", "chunk": "……原文片段……", "score": 0.87 } ],
  "conversationId": "c1a2b3"
}
```

### 流式问答 `POST /api/chat/stream`

SSE 事件流：`meta`（会话 ID + 引用）→ `token` × N（增量文本）→ `done`。

### 纯检索验证 `POST /api/chat/search`

只返回命中的相关片段（不调用 LLM），用于验证检索质量：

```json
{ "question": "年假有多少天" }
```

### 文档管理 `/api/documents`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/documents` | 上传单个文档入库（`multipart`，字段 `file`） |
| POST | `/api/documents/batch` | 批量上传（字段 `files`） |
| GET | `/api/documents` | 列出已入库文档及分块数 |
| DELETE | `/api/documents?source=xxx` | 按文件名删除文档全部分块 |

## 配置说明（application.properties）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `DEEPSEEK_API_KEY` | 空 | DeepSeek 对话模型密钥 |
| `EMBEDDING_API_KEY` | 空 | SiliconFlow 密钥（Embedding） |
| `RERANK_API_KEY` | 空 | SiliconFlow 密钥（Rerank，可关：`RERANK_ENABLED=false`） |
| `DEEPSEEK_CHAT_MODEL` | `deepseek-chat` | 对话模型 |
| `EMBEDDING_MODEL` | `BAAI/bge-m3` | Embedding 模型（维度须与建表一致，1024） |
| `RERANK_MODEL` | `BAAI/bge-reranker-v2-m3` | 重排模型 |
| `SPRING_PROFILES_ACTIVE` | `local` | 默认激活 local profile（读取 application-local.properties） |

## 安全说明

- API Key、数据库密码等敏感信息**只允许**写在 `application-local.properties` 或环境变量中
- 该文件已被 `.gitignore` 排除，提交代码前请确认 `git status` 中不包含它
