# Motcs Commons — GraphRAG 多跳智能搜索系统

基于 **Spring Boot 4.1.1 + Neo4j + Spring AI 2.0** 的企业级文档知识库与多跳智能问答系统。采用纯 WebFlux
响应式架构，支持文档异步入库、企业级双层切分、向量检索 + 知识图谱多跳推理、流式对话、引用溯源等核心能力。

---

## 功能特性

### 智能问答

- 多轮对话，基于 `sessionId` 自动关联上下文
- SSE 流式输出，AI 回答实时渲染 Markdown
- 向量检索 + Neo4j 多跳图谱召回，双路融合
- 每轮回答同步展示引用的知识库片段，可点击查看原文（含页码导航）
- 对话记录持久化到 Neo4j，按用户/租户/系统隔离
- 支持对话导出为 Markdown、单条/批量删除
- 无搜索结果时给出明确提示（未上传文档 / 处理中 / 内容不匹配）

### 文档管理

- 支持 PDF / Word / Excel / PPT / TXT / CSV / Markdown 等 10 种格式
- 上传后立即返回，图谱构建异步执行，不阻塞接口
- 处理状态实时轮询：处理中 → 已完成 / 处理失败
- 企业级双层切分（细粒度检索 + 粗粒度推理），保留页码与标题层级
- 按 docCode 级联删除：分片、向量、知识点、关联关系、原始文件
- 文档删除后对话引用同步标记"已删除"，确保数据一致性
- 多租户 + 多系统类型隔离，用户仅可删除自己上传的文档
- 文档统计接口（轻量聚合查询，百万级文档无压力）
- 上传失败文档支持重新上传，表单自动反填

### 知识图谱

- 实体抽取与关系构建，可视化力导向图
- 图谱稳定后自动停止物理模拟，支持重新布局
- 按租户/系统过滤，限制节点数量避免性能问题

### 系统架构

- 纯 Spring WebFlux 响应式，虚拟线程 + Reactor
- Neo4j 同时作为向量数据库（VectorStore）和图数据库
- Jib 容器化打包，环境变量驱动配置，无需重新构建
- Jackson 3.x 全局日期格式化，JVM 时区固定 Asia/Shanghai

---

## 技术栈

| 类别     | 技术                                                               |
|----------|--------------------------------------------------------------------|
| 框架     | Spring Boot 4.1.1、Spring WebFlux                                  |
| AI       | Spring AI 2.0.0、OpenAI 兼容接口（智谱 GLM-4-Flash / embedding-3） |
| 数据库   | Neo4j（向量 + 图谱一体化）                                         |
| 文档处理 | Apache PDFBox 2.0.29、Apache POI 5.4.0、commons-io 2.18.0          |
| 构建     | Gradle 9.7.0、Jib 3.5.4                                            |
| 语言     | Java 26                                                            |
| 前端     | 原生 HTML + Tailwind CSS + marked.js + vis-network                 |

---

## 快速开始

### 环境要求

- JDK 26+
- Neo4j 5.x / 2026.x（需启用向量索引）
- 智谱 AI API Key（或其他 OpenAI 兼容接口）

### 本地运行

1. **启动 Neo4j**

   参考 [NEO4J.md](./NEO4J.md) 完成 Neo4j 安装与启动。

2. **配置环境变量**（或修改 `application-prod.yaml`）

   ```bash
   export NEO4J_URI=bolt://127.0.0.1:7687
   export NEO4J_AUTH_USERNAME=neo4j
   export NEO4J_AUTH_PASSWORD=your_password
   export AI_API_KEY=your_zhipu_api_key
   export AI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
   ```

3. **启动应用**

   ```bash
   ./gradlew bootRun
   ```

4. **访问前端**

   浏览器打开 `http://localhost:8080/`

---

## Docker 部署

### 构建镜像

```bash
# 设置镜像仓库凭据
export DOCKER_USERNAME=your_username
export DOCKER_PASSWORD=your_password
export DOCKER_URL=your-registry.com

# 构建并推送
./gradlew jib
```

### Docker Compose

```yaml
version: '3.8'
services:
  motcs-commons:
    image: your-registry.com/motcs/motcs-commons:latest
    environment:
      - NEO4J_URI=bolt://neo4j:7687
      - NEO4J_AUTH_USERNAME=neo4j
      - NEO4J_AUTH_PASSWORD=your_password
      - AI_API_KEY=your_api_key
      - AI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
    ports:
      - "8080:8080"
    volumes:
      - ./uploads:/app/uploads
    restart: always
```

---

## 环境变量配置

### 核心配置

| 变量名                | 默认值                  | 说明           |
|-----------------------|-------------------------|----------------|
| `SERVER_PORT`         | `8080`                  | 服务端口       |
| `NEO4J_URI`           | `bolt://127.0.0.1:7687` | Neo4j 连接地址 |
| `NEO4J_AUTH_USERNAME` | `neo4j`                 | Neo4j 用户名   |
| `NEO4J_AUTH_PASSWORD` | -                       | Neo4j 密码     |

### AI 配置

| 变量名                   | 默认值                                 | 说明                       |
|--------------------------|----------------------------------------|----------------------------|
| `AI_BASE_URL`            | `https://open.bigmodel.cn/api/paas/v4` | AI 接口地址（OpenAI 兼容） |
| `AI_API_KEY`             | -                                      | AI API 密钥                |
| `AI_CHAT_MODEL`          | `glm-4-flash`                          | 对话模型                   |
| `AI_CHAT_TEMPERATURE`    | `0.1`                                  | 对话温度                   |
| `AI_EMBEDDING_MODEL`     | `embedding-3`                          | 向量模型                   |
| `AI_EMBEDDING_DIMENSION` | `2048`                                 | 向量维度                   |

### 文件配置

| 变量名              | 默认值                                      | 说明                       |
|---------------------|---------------------------------------------|----------------------------|
| `FILE_UPLOAD_DIR`   | `./uploads`                                 | 文件上传目录               |
| `FILE_MAX_SIZE`     | `52428800`                                  | 最大文件大小（50MB）       |
| `SUPPORTED_FORMATS` | `pdf,doc,docx,xls,xlsx,ppt,pptx,txt,csv,md` | 支持的文件格式（逗号分隔） |

### 镜像构建（Jib）

| 变量名            | 说明           |
|-------------------|----------------|
| `DOCKER_USERNAME` | 镜像仓库用户名 |
| `DOCKER_PASSWORD` | 镜像仓库密码   |
| `DOCKER_URL`      | 镜像仓库地址   |

---

## API 接口

### 文档管理

| 方法     | 路径                               | 说明                                                                                               |
|----------|------------------------------------|----------------------------------------------------------------------------------------------------|
| `POST`   | `/api/documents/upload`            | 上传文档（multipart/form-data，字段：file/title/description/docCode/tenantCode/systemType/userId） |
| `POST`   | `/api/documents/upload-by-url`     | 通过 URL 上传文档                                                                                  |
| `GET`    | `/api/documents/list`              | 查询文档列表（tenantCode + systemType）                                                            |
| `DELETE` | `/api/documents/docCode/{docCode}` | 按 docCode 删除文档（级联删除分片/向量/实体/关系/文件）                                            |
| `GET`    | `/api/documents/stats`             | 文档统计（文档数 + 分片数，轻量聚合）                                                              |
| `GET`    | `/api/documents/health`            | 健康检查                                                                                           |

### 智能问答

| 方法   | 路径                   | 说明                                                                             |
|--------|------------------------|----------------------------------------------------------------------------------|
| `POST` | `/api/documents/query` | GraphRAG 问答（SSE 流式，参数：question/userId/sessionId/tenantCode/systemType） |

**SSE 响应顺序：**

```
__SESSION__:sess_xxx        # 会话ID（首次自动生成）
__SOURCES__:[{...}]          # 引用的知识库片段
data:回答内容逐token输出...   # AI 回答（Markdown格式）
```

### 对话记录

| 方法     | 路径                                               | 说明                                                            |
|----------|----------------------------------------------------|-----------------------------------------------------------------|
| `GET`    | `/api/documents/sessions`                          | 会话列表（按 userId + tenantCode + systemType，去重 sessionId） |
| `GET`    | `/api/documents/conversations/session`             | 按 sessionId 查询完整多轮对话                                   |
| `GET`    | `/api/documents/conversations`                     | 按用户查询对话记录                                              |
| `DELETE` | `/api/documents/conversations/session/{sessionId}` | 删除单个会话（含所有对话记录）                                  |
| `DELETE` | `/api/documents/conversations/batch`               | 批量删除会话（Body: sessionId 数组）                            |

### 知识图谱

| 方法  | 路径                   | 说明                                                         |
|-------|------------------------|--------------------------------------------------------------|
| `GET` | `/api/documents/graph` | 获取图谱数据（tenantCode + systemType + limit，默认200节点） |

---

## 企业级切分规则

系统采用双层切分架构，适配多跳 RAG 场景：

| 层级   | 定位         | Token 范围  | 重叠率 |
|--------|--------------|-------------|--------|
| 细粒度 | 向量检索召回 | 384 ~ 512   | 25%    |
| 粗粒度 | 多跳推理补全 | 1024 ~ 1536 | 20%    |

**核心规则：**

- 语义结构优先，不硬截断完整句子/条款/列表/表格
- 每个切片携带完整标题层级、页码范围（支持跨页 `1-2`）
- 细粒度切片绑定父级粗粒度切片，检索后自动联动上下文
- 元数据包含 docId、chunkId、parentChunkId、prev/next 关联、titleHierarchy、tokenSize 等标准字段
- 超大文档（>200片）实体抽取自动限流，避免 AI 接口 429

---

## 配置说明

### Neo4j 配置

Spring Boot 4.x 的 `spring-boot-starter-neo4j` 仅自动配置 Driver，需手动声明 `Neo4jTemplate` / `MappingContext`（见
`Neo4jConfig.java`）。启动时自动创建 `KnowledgeEntity.name` 唯一约束，防止并行实体抽取竞态。

### Jackson 3.x 配置

Spring Boot 4.1.1 使用 Jackson 3.x（包名 `tools.jackson`），通过 `JsonMapperBuilderCustomizer` 注册自定义
`LocalDateTimeSerializer`，全局格式 `yyyy-MM-dd HH:mm:ss`。

### 时区配置

启动类 `MotcsCommonsApplication.main()` 最前面设置 `TimeZone.setDefault(Asia/Shanghai)`，确保容器内时间正确。

### 向量库配置

Neo4jVectorStore 使用 label=`DocumentChunk`，index-name=`knowledge_vector_index`，距离类型 cosine，维度 2048。细粒度切片 ID
与向量节点相同，禁止使用 `saveAll`（会覆盖 embedding），必须通过 Cypher UPDATE 更新属性。

---

## 常见问题

**Q: 启动报 `The client is unauthorized due to authentication failure`**
A: Neo4j 账号密码不正确，检查 `NEO4J_AUTH_USERNAME` / `NEO4J_AUTH_PASSWORD` 环境变量。

**Q: 上传 docx 报 `NoSuchMethodError: BoundedInputStream.builder()`**
A: POI 5.4.0 需要 commons-io 2.16+，项目已升级到 2.18.0。

**Q: AI 回答报 429 限流**
A: 大文档实体抽取已做批量合并 + 并发限制（最多200片、并发2、批次间隔1.5s），多跳召回限制30条。如仍触发，请降低
`AI_CHAT_TEMPERATURE` 或升级 API 配额。

**Q: 文档一直显示"处理中"**
A: 图谱构建为异步操作，前端每30秒轮询状态。若长时间未完成，检查日志中实体抽取是否超时或 API 密钥是否过期。

**Q: Docker 容器时间比北京时间少8小时**
A: 已在启动类固定 JVM 时区为 Asia/Shanghai，确保使用最新镜像。

---

## 项目结构

```
motcs-commons/
├── src/main/java/com/motcs/
│   ├── MotcsCommonsApplication.java        # 启动类（时区配置）
│   ├── controller/
│   │   └── DocumentController.java         # 全量 WebFlux 控制器（13个接口）
│   ├── service/
│   │   └── DocumentService.java            # 文档服务（异步入库、级联删除、聚合查询）
│   ├── knowledge/
│   │   ├── graph/
│   │   │   ├── GraphRagKnowledgeService.java   # GraphRAG 核心（检索+多跳+流式+对话记录）
│   │   │   ├── GraphRagQuery.java              # 问答请求 DTO
│   │   │   └── GraphRagMultiHopResult.java     # 多跳召回结果
│   │   ├── chunk/
│   │   │   ├── DocumentChunk.java              # 分片节点实体（含页码/层级/关联ID）
│   │   │   └── DocumentChunkRepository.java    # 分片仓库（聚合查询/Cypher多跳）
│   │   ├── record/
│   │   │   ├── ConversationRecord.java         # 对话记录实体
│   │   │   └── ConversationRecordRepository.java
│   │   ├── KnowledgeEntity.java                # 知识图谱实体
│   │   └── KnowledgeEntityRepository.java
│   ├── dto/                                # 请求/响应 DTO（DocumentSummary/Stats/Upload等）
│   ├── util/
│   │   ├── AnyDocConverterUtil.java        # 多格式文档转 Markdown（PDF/Word/Excel/PPT）
│   │   ├── EnterpriseChunker.java          # 企业级双层切分器
│   │   └── ByteArrayMultipartFile.java     # WebFlux 文件适配
│   ├── commons/
│   │   ├── Utils.java                      # 通用工具（格式校验/缓冲区合并）
│   │   └── FileUtils.java                  # 文件工具（页码提取/范围解析）
│   └── config/
│       ├── Neo4jConfig.java                # Neo4j 手动配置（Template/Mapping/约束）
│       ├── AiConfig.java                   # AI 配置（ChatClient/EmbeddingModel分离）
│       └── WebConfiguration.java           # Jackson 3.x 日期格式化
├── src/main/resources/
│   ├── application.yaml                    # 基础配置（Jackson/虚拟线程/HTTP2）
│   ├── application-prod.yaml               # 生产配置（环境变量占位）
│   └── static/                             # 前端单页应用
│       ├── index.html                      # 主页面（三标签：问答/文档/图谱）
│       ├── css/style.css                   # 深色主题样式
│       ├── js/app.js                       # 前端交互逻辑
│       ├── js/marked.min.js                # Markdown 渲染
│       ├── js/vis-network.min.js           # 图谱可视化
│       └── favicon.ico
├── build.gradle                            # Gradle + Jib 配置
├── NEO4J.md                                # Neo4j 安装部署指南
└── README.en.md                            # 英文文档
```

---

## 鸣谢

本项目使用了以下优秀的开源项目，在此向它们的开发者和贡献者表示感谢：

### 后端框架与运行时

| 项目                                                                                 | 版本    | 用途                                    | 许可证     |
|--------------------------------------------------------------------------------------|---------|-----------------------------------------|------------|
| [Spring Boot](https://spring.io/projects/spring-boot)                                | 4.1.1   | 应用框架                                | Apache 2.0 |
| [Spring WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html) | 4.1.1   | 响应式 Web 框架                         | Apache 2.0 |
| [Spring AI](https://spring.io/projects/spring-ai)                                    | 2.0.0   | AI 抽象层（向量存储/对话模型/对话记忆） | Apache 2.0 |
| [Project Reactor](https://projectreactor.io/)                                        | -       | 响应式编程库                            | Apache 2.0 |
| [Lombok](https://projectlombok.org/)                                                 | 1.18.46 | 编译时代码生成                          | MIT        |
| [Jackson 3.x](https://github.com/FasterXML/jackson)                                  | 2.21.+  | JSON 序列化/反序列化                    | Apache 2.0 |

### 数据库与驱动

| 项目                                                              | 版本         | 用途                   | 许可证       |
|-------------------------------------------------------------------|--------------|------------------------|--------------|
| [Neo4j](https://neo4j.com/)                                       | 5.x / 2026.x | 图数据库 + 向量数据库  | GPLv3 / 商业 |
| [Neo4j Java Driver](https://github.com/neo4j/neo4j-java-driver)   | 6.1.0        | Neo4j 官方驱动         | Apache 2.0   |
| [Spring Data Neo4j](https://spring.io/projects/spring-data-neo4j) | 8.1.1        | Neo4j ORM / Repository | Apache 2.0   |

### 文档处理

| 项目                                                                               | 版本   | 用途                    | 许可证     |
|------------------------------------------------------------------------------------|--------|-------------------------|------------|
| [Apache PDFBox](https://pdfbox.apache.org/)                                        | 2.0.29 | PDF 文本提取            | Apache 2.0 |
| [Apache POI](https://poi.apache.org/)                                              | 5.4.0  | Word/Excel/PPT 文档解析 | Apache 2.0 |
| [Apache Commons IO](https://commons.apache.org/proper/commons-io/)                 | 2.18.0 | 文件 I/O 工具           | Apache 2.0 |
| [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) | 1.6.0  | 文件上传处理            | Apache 2.0 |

### AI 服务

| 项目                                            | 用途                                                           |
|-------------------------------------------------|----------------------------------------------------------------|
| [智谱 AI (Zhipu AI)](https://open.bigmodel.cn/) | GLM-4-Flash 对话模型 + embedding-3 向量模型（OpenAI 兼容接口） |

### 前端

| 项目                                                | 用途                   | 许可证           |
|-----------------------------------------------------|------------------------|------------------|
| [Tailwind CSS](https://tailwindcss.com/)            | 原子化 CSS 框架        | MIT              |
| [marked.js](https://github.com/markedjs/marked)     | Markdown 渲染          | MIT              |
| [vis-network](https://github.com/visjs/vis-network) | 知识图谱力导向图可视化 | Apache 2.0 / MIT |

### 构建与部署

| 项目                                               | 版本  | 用途                            | 许可证     |
|----------------------------------------------------|-------|---------------------------------|------------|
| [Gradle](https://gradle.org/)                      | 9.7.0 | 构建工具                        | Apache 2.0 |
| [Jib](https://github.com/GoogleContainerTools/jib) | 3.5.4 | 容器镜像构建（无需 Dockerfile） | Apache 2.0 |

---

## 许可证

本项目采用 **MIT 许可证（附商用限制）**。

- **个人非商业研究/学习使用**：免费、自由，可使用、复制、修改、分发
- **商业使用**：需联系作者获得书面授权，包括但不限于集成到商业产品、付费服务、任何以商业盈利为目的的使用

完整协议见 [LICENSE](./LICENSE)。
