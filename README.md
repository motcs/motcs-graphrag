# Motcs Graphrag — GraphRAG 多跳智能搜索系统

> **中文** | [English](./README.en.md)

基于 **Spring Boot 4.1.1 + Neo4j + MySQL + Spring AI 2.0.1** 的企业级文档知识库与多跳智能问答系统。采用纯 WebFlux
响应式架构，支持文档异步入库、企业级双层切分、向量检索 + 知识图谱多跳推理、流式对话、引用溯源等核心能力，
内置**超管登录 + API Key 鉴权**，接口权限分级管控。

---

## 功能特性

### 智能问答

- 多轮对话，基于 `sessionId` 自动关联上下文
- **模型可选**：对话框下方下拉框切换 `deepseek-v3.2` / `deepseek-v3.2-think` / `deepseek-v4-flash-0731`
  （下拉选项由后端 `GET /ai/v1/provider` 按当前启用的 AI 平台动态下发，避免选到不存在的模型）
- SSE 流式输出（JSON 事件：session / sources / reasoning / content），AI 回答实时渲染 Markdown
- 向量检索 + Neo4j 多跳图谱召回，双路融合
- 每轮回答同步展示引用的知识库片段，可点击查看原文（含页码导航）
- 对话记录持久化（Neo4j + MySQL），按用户/租户/系统隔离；API Key 对话按 Key 归属隔离
- 支持对话导出为 Markdown、单条/批量删除
- 无搜索结果时给出明确提示（未上传文档 / 处理中 / 内容不匹配）

### 文档管理

- 支持 PDF / Word / Excel / PPT / TXT / CSV / Markdown 等 10 种格式
- **Word 转换兼容表格型文档**：按文档顺序遍历段落 + 表格（含嵌套表格），工作类 docx（内容在表格中）也能完整提取
- 上传后立即返回，图谱构建异步执行，不阻塞接口
- 处理状态实时轮询：处理中 → 已完成 / 处理失败
- 企业级双层切分（细粒度检索 + 粗粒度推理），保留页码与标题层级
- 按 docCode 级联删除：分片、向量、知识点、关联关系、原始文件
- 文档删除后对话引用同步标记"已删除"，确保数据一致性
- 多租户 + 多系统类型隔离；超管（租户 `0`）上传的文档对所有租户开放，用户仅可删除自己上传的文档
- 文档统计接口（轻量聚合查询，百万级文档无压力）
- 上传失败文档支持重新上传，表单自动反填

### 知识图谱

- 实体抽取与关系构建，可视化力导向图
- 图谱稳定后自动停止物理模拟，支持重新布局
- 按租户/系统过滤：租户 `0` 查全部关系，指定租户只查「本租户 + 全局租户(0)」文档涉及的关系
- 限制节点数量避免性能问题

### 认证与权限控制

> **租户语义**：租户 `0` 为超管全局租户。
> - **文档/对话上下文**：租户 `0` 查询时**不过滤租户，检索所有租户的全部文档**；指定租户查询时同时检索「本租户文档 + 租户 0 全局文档」，本租户内容优先。
> - **对话记录**：始终按租户隔离——超管（租户 `0`）只能查看自己（租户 `0`）的对话记录，API Key 对话按 Key 归属隔离。
> - **API Key**：生成时不允许绑定租户 `0`。

- **超管登录**：唯一管理账号（环境变量配置），登录成功后签发 x-token（带过期时间），后续请求携带 `x-token` 请求头完成鉴权
- **API Key 鉴权**：超管生成 Key 后人工分发，调用方携带 Key 即可访问 **API Key 专属对话接口**（`/keys/v1/**`）；**Key 绑定租户 + 系统类型**，对话时自动以此检索对应租户的知识库
- **API Key 专属对话接口**：`POST /keys/v1/chat`，调用方只需传 问题 / 用户编码 / 会话ID，租户与系统类型由 Key 绑定值自动赋值
- 接口权限分级：公开 / 仅超管登录 / 仅 API Key 三类（详见 [认证与权限控制](#认证与权限控制)）
- API Key 参考 OpenAI 设计：数据库只存哈希、明文仅生成时展示一次、备注/租户/系统类型必填

### 系统架构

- 纯 Spring WebFlux 响应式，虚拟线程 + Reactor
- Neo4j 同时作为向量数据库（VectorStore）和图数据库；MySQL（R2DBC）存储 API Key、对话记录等业务数据
- Spring Security（WebFlux）提供认证链路：会话登录 + 无状态 API Key 双通道
- CSRF 双提交 Cookie 防护（仅 POST 校验，API Key 请求豁免）
- Jib 容器化打包，环境变量驱动配置，无需重新构建
- Jackson 3.x 全局日期格式化，JVM 时区固定 Asia/Shanghai

---

## 技术栈

| 类别     | 技术                                                               |
|----------|--------------------------------------------------------------------|
| 框架     | Spring Boot 4.1.1、Spring WebFlux、Spring Security 7.1             |
| AI       | Spring AI 2.0.1、OpenAI 兼容接口（百度千帆，可选智谱）             |
| 数据库   | Neo4j（向量 + 图谱一体化）、MySQL 8.x（R2DBC）                     |
| 文档处理 | Apache PDFBox 2.0.29、Apache POI 5.4.0、commons-io 2.18.0          |
| 构建     | Gradle 9.7.0、Jib 3.5.4                                            |
| 语言     | Java 26                                                            |
| 前端     | 原生 HTML + Tailwind CSS + marked.js + vis-network                 |

---

## 快速开始

### 环境要求

- JDK 26+
- Neo4j 5.x / 2026.x（需启用向量索引）
- MySQL 8.x（R2DBC，用于 `api_key` 等业务表，启动时自动建表）
- 百度千帆 API Key（`bce-v3/...` 开头，控制台申请后需**手动开通**所需模型）

### 本地运行

1. **启动 Neo4j 与 MySQL**

   参考 [NEO4J.md](./NEO4J.md) 完成 Neo4j 安装与启动；MySQL 创建数据库（默认库名 `motcs`）。

2. **配置环境变量**（或修改 `application-baidu.yaml`）

   ```bash
   export SPRING_PROFILES_ACTIVE=baidu
   export NEO4J_URI=bolt://127.0.0.1:7687
   export NEO4J_AUTH_USERNAME=neo4j
   export NEO4J_AUTH_PASSWORD=your_password
   export MYSQL_HOST=127.0.0.1
   export MYSQL_PORT=3306
   export MYSQL_DATABASE=motcs
   export MYSQL_USERNAME=root
   export MYSQL_PASSWORD=your_password
   # 百度千帆
   export AI_API_KEY=your_qianfan_api_key
   export AI_BASE_URL=https://qianfan.baidubce.com/v2
   # 超管账号（生产环境必改）
   export AUTH_USERNAME=admin
   export AUTH_PASSWORD=your_strong_password
   ```

3. **启动应用**

   ```bash
   ./gradlew bootRun
   ```

   或显式指定 profile：

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=baidu'
   ```

4. **访问前端**

   浏览器打开 `http://localhost:8080/`，使用超管账号登录后进入管理界面。

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
  motcs-graphrag:
    image: your-registry.com/motcs/motcs-graphrag:latest
    environment:
      - SPRING_PROFILES_ACTIVE=baidu
      - NEO4J_URI=bolt://neo4j:7687
      - NEO4J_AUTH_USERNAME=neo4j
      - NEO4J_AUTH_PASSWORD=your_password
      - MYSQL_HOST=mysql
      - MYSQL_DATABASE=motcs
      - MYSQL_USERNAME=root
      - MYSQL_PASSWORD=your_password
      - AI_API_KEY=your_qianfan_api_key
      - AI_BASE_URL=https://qianfan.baidubce.com/v2
      - AUTH_USERNAME=admin
      - AUTH_PASSWORD=your_strong_password
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
| `SPRING_PROFILES_ACTIVE` | `prod`               | 激活 profile（百度千帆用 `baidu`） |
| `NEO4J_URI`           | `bolt://127.0.0.1:7687` | Neo4j 连接地址 |
| `NEO4J_AUTH_USERNAME` | `neo4j`                 | Neo4j 用户名   |
| `NEO4J_AUTH_PASSWORD` | -                       | Neo4j 密码     |
| `MYSQL_HOST`          | `127.0.0.1`             | MySQL 地址     |
| `MYSQL_PORT`          | `3306`                  | MySQL 端口     |
| `MYSQL_DATABASE`      | `motcs`                 | MySQL 库名     |
| `MYSQL_USERNAME`      | `root`                  | MySQL 用户名   |
| `MYSQL_PASSWORD`      | -                       | MySQL 密码     |

### AI 配置（百度千帆）

| 变量名                   | 默认值                                 | 说明                                  |
|--------------------------|----------------------------------------|---------------------------------------|
| `AI_BASE_URL`            | `https://qianfan.baidubce.com/v2`      | 千帆 OpenAI 兼容接口地址              |
| `AI_API_KEY`             | -                                      | 千帆 API Key（`bce-v3/...`）          |
| `AI_CHAT_MODEL`          | `deepseek-v3.2-think`                  | 对话模型（三个可选，见[模型选择](#模型选择)） |
| `AI_CHAT_TEMPERATURE`    | `0.1`                                  | 对话温度                              |
| `AI_TIMEOUT`             | `300s`                                 | AI 接口超时（思考模型建议保持 5 分钟） |
| `AI_EMBEDDING_MODEL`     | `bge-large-zh`                         | 向量模型（1024 维，中文）             |
| `AI_EMBEDDING_DIMENSION` | `1024`                                 | 向量维度（必须与向量模型一致）        |
| `AI_PROVIDER`            | `baidu`                                | 当前 AI 平台标识（前端据此渲染模型下拉）|
| `AI_CHAT_MODELS`         | `deepseek-v3.2,deepseek-v3.2-think,deepseek-v4-flash-0731` | 可选模型列表（逗号分隔）|

> 千帆向量模型 `bge-large-zh` 为 **1024 维**，单次提交约 **16 条**文本上限。若从智谱
> `embedding-3`（2048 维）迁移，需重建向量索引（见[向量库配置](#向量库配置)）。

### 认证配置

| 变量名         | 默认值 | 说明                                     |
|----------------|--------|------------------------------------------|
| `AUTH_USERNAME`| `xxhzj`（见 application-baidu.yaml） | 超管登录用户名，生产环境必须覆盖 |
| `AUTH_PASSWORD`| -      | 超管登录密码，生产环境必须覆盖           |
| `AUTH_TOKEN_TTL`| `7200` | x-token 过期时间（秒），每次有效使用自动续期 |
| `API_KEY_LEN`  | `40`   | API Key 随机部分长度（前缀 `sk-` 之外）  |

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

## 认证与权限控制

> **租户语义**：租户 `0` 为超管全局租户。
> - **文档/对话上下文**：租户 `0` 查询时**不过滤租户，检索所有租户的全部文档**；指定租户查询时同时检索「本租户文档 + 租户 0 全局文档」，本租户内容优先。
> - **对话记录**：始终按租户隔离——超管（租户 `0`）只能查看自己（租户 `0`）的对话记录，API Key 对话按 Key 归属隔离。
> - **API Key**：生成时不允许绑定租户 `0`。

系统采用 **Spring Security（WebFlux）** 双通道认证：**超管会话登录** 与 **API Key 无状态鉴权**。

### 1. 登录方式

| 方式     | 说明                                                                                                  |
|----------|-------------------------------------------------------------------------------------------------------|
| 超管登录 | `POST /auth/v1/login` **HTTP Basic Auth**（`Authorization: Basic base64(username:password)`），由 Spring Security Basic 认证过滤链校验（`AUTH_USERNAME` / `AUTH_PASSWORD`），成功后**签发 x-token 返回**，后续请求携带请求头 `x-token` 即视为已登录 |
| API Key  | 请求头携带 `Authorization: Bearer sk-...` 或 `X-API-Key: sk-...`，无状态校验（每次请求比对 SHA-256 哈希），适合机器/第三方调用 |

### 2. API Key 设计（参考 OpenAI）

- **格式**：`sk-` + 40 位随机字符（默认长度可配 `API_KEY_LEN`，去除易混淆字符 `0O1lI`）
- **存储**：数据库只保存 SHA-256 哈希与前缀掩码（如 `sk-Ab3Xy7...`），**明文仅在生成时返回一次**，丢失需重新生成
- **备注必填**：生成时 `name`（备注）必填，为空返回 `400`
- **租户/系统必填**：生成时必须绑定 `tenantCode`（具体租户编码）与 `systemType`（系统类型），对话/上传时以此归属；**不允许绑定租户 `0`**（超管全局租户），传 `0` 返回 `400`
- **权限**：API Key 认证身份为 `ROLE_API_KEY`，**仅可访问 `/keys/v1/**` 专属接口**（AI 对话与对话历史），无法访问 `/documents/v1/**`、`/auth/v1/**` 等管理/业务接口（403）
- **管理**：仅超管可生成 / 查看列表 / 删除（删除后携带该 Key 的请求立即失效）

### 3. 接口权限矩阵

| 路径 | 权限 |
|------|------|
| `/`、`/index.html`、`/css/**`、`/js/**`、`/img/**`、favicon、Swagger 文档（`/v3/api-docs/**`、`/swagger-ui/**`、`/webjars/**`） | **公开** |
| `POST /auth/v1/login` | **公开** |
| `GET /documents/v1/health`（健康检查） | **公开** |
| `GET /ai/v1/provider`（AI 平台探测，登录前渲染模型下拉） | **公开** |
| `POST /auth/v1/logout`、`GET /auth/v1/me` | **仅超管登录** |
| `GET/POST /auth/v1/api-keys`、`DELETE /auth/v1/api-keys/{id}`（API Key 管理） | **仅超管登录** |
| **`POST /documents/v1/query`（AI 对话接口）** | **仅超管登录** |
| **`/keys/v1/**`（API Key 专属：`/chat` 对话 + `/conversations` 历史查询/删除）** | **仅 API Key**（登录用户 403） |
| 其余所有业务接口（上传/列表/删除/统计/图谱/会话记录等） | **仅超管登录** |

### 4. 调用示例

**超管登录（Basic Auth，换取 x-token）**

```bash
curl -X POST http://localhost:8080/auth/v1/login \
  -H "Authorization: Basic $(echo -n 'admin:your_password' | base64)"
```

> Windows PowerShell 生成 Basic 头：`[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:your_password'))`

成功返回：`{"token":"xxx","expires":7200,"lastAccessTime":...}`，后续请求携带 `x-token` 请求头调用业务接口。
token 登记到 TokenStore，**带过期时间（默认 2 小时，可配置 `AUTH_TOKEN_TTL`，每次有效使用自动续期/滑动过期）**，过期后自动失效需重新登录。

**CSRF 防护（双提交 Cookie 模式，仅 POST 校验）**：CSRF token 由后端经响应头 **`Set-Cookie: XSRF-TOKEN=...`** 下发（httpOnly=false），前端读取 cookie 后，在登录态的 **POST** 请求头携带 `X-CSRF-TOKEN`，后端比对请求头与 cookie 值；**GET 及其它方法不校验 CSRF**。豁免：登录接口、`/keys/v1/**` 专属接口（API Key 专属路径，无 cookie 会话）。

**超管登录调用 AI 对话接口（SSE 流式）**

```bash
# 1) 登录获取 x-token（Basic Auth）
curl -c cookies.txt -X POST http://localhost:8080/auth/v1/login \
  -H "Authorization: Basic $(echo -n 'admin:your_password' | base64)"
# 2) 携带 x-token + CSRF 头（值取自 Set-Cookie: XSRF-TOKEN）调用
curl -N -b cookies.txt -X POST http://localhost:8080/documents/v1/query \
  -H "x-token: tok-xxx..." -H "X-CSRF-TOKEN: <XSRF-TOKEN值>" \
  -H "Content-Type: application/json" \
  -d '{"question":"什么是多跳检索？","userId":"xxhzj","tenantCode":"0","systemType":"other"}'
```

**用 API Key 调用专属对话接口（租户/系统自动从 Key 读取，只需传 问题/用户编码/会话ID）**

```bash
curl -N -X POST http://localhost:8080/keys/v1/chat \
  -H "X-API-Key: sk-Ab3Xy7..." \
  -H "Content-Type: application/json" \
  -d '{"question":"会议的核心内容是什么？","userId":"test0101","sessionId":"sess_001"}'
```

**生成 API Key（需登录，备注/租户/系统类型必填）**

```bash
curl -X POST http://localhost:8080/auth/v1/api-keys \
  -H "Content-Type: application/json" \
  -H "x-token: tok-xxx..." \
  -d '{"name":"第三方系统对接","tenantCode":"410725","systemType":"congress"}'
```

**未认证访问业务接口** → `401` `{"code":401,"message":"未登录或登录已过期"}`
**API Key 越权访问管理接口** → `403` `{"code":403,"message":"无权限访问"}`
**登录用户访问 `/keys/v1/**`** → `403`（仅 API Key 可用）

---

## API 接口

> 除标注"公开"外，所有接口均需认证（超管登录或 API Key，见[接口权限矩阵](#3-接口权限矩阵)）。

### 认证与 API Key 管理

| 方法     | 路径                         | 权限           | 说明                                                        |
|----------|------------------------------|----------------|-------------------------------------------------------------|
| `POST`   | `/auth/v1/login`            | 公开           | 超管登录（Basic Auth：Authorization: Basic base64(username:password)） |
| `POST`   | `/auth/v1/logout`           | 仅登录         | 退出登录（注销 x-token，从请求头读取）                      |
| `GET`    | `/auth/v1/me`               | 仅登录         | 当前登录状态                                                |
| `GET`    | `/auth/v1/api-keys`         | 仅登录         | Key 列表（仅掩码，不含哈希与明文）                          |
| `POST`   | `/auth/v1/api-keys`         | 仅登录         | 生成新 Key（Body：name 备注/**tenantCode 租户**/**systemType 系统**均必填；返回明文一次） |
| `DELETE` | `/auth/v1/api-keys/{id}`    | 仅登录         | 删除 Key（立即失效）                                        |

### AI 平台探测

| 方法  | 路径                | 权限   | 说明                                                                   |
|-------|---------------------|--------|------------------------------------------------------------------------|
| `GET` | `/ai/v1/provider`  | 公开   | 当前启用 AI 平台（provider/providerName）+ 可选模型列表（chatModels），前端据此渲染模型下拉框 |

### 文档管理

| 方法     | 路径                               | 权限   | 说明                                                                                               |
|----------|------------------------------------|--------|----------------------------------------------------------------------------------------------------|
| `POST`   | `/documents/v1/upload`            | 仅登录 | 上传文档（multipart/form-data，字段：file/title/description/docCode/tenantCode/systemType/userId） |
| `POST`   | `/documents/v1/upload/url`        | 仅登录 | 通过 URL 上传文档（Body：FileUploadRequest）                                                        |
| `GET`    | `/documents/v1/list`              | 仅登录 | 查询文档列表（tenantCode + systemType；租户 0 查全部）                                              |
| `DELETE` | `/documents/v1/docCode/{docCode}` | 仅登录 | 按 docCode 删除文档（级联删除分片/向量/实体/关系/文件）                                            |
| `GET`    | `/documents/v1/stats`             | 仅登录 | 文档统计（文档数 + 分片数，轻量聚合）                                                              |
| `GET`    | `/documents/v1/health`            | 公开   | 健康检查                                                                                           |

### 智能问答

| 方法   | 路径                   | 权限                    | 说明                                                                                             |
|--------|------------------------|-------------------------|--------------------------------------------------------------------------------------------------|
| `POST` | `/documents/v1/query` | **仅超管登录** | GraphRAG 问答（SSE 流式，参数：question/userId/sessionId/tenantCode/systemType/model 可选）      |
| `POST` | `/keys/v1/chat`       | **仅 API Key**          | API Key 专属问答（SSE 流式，参数：question/userId/sessionId 可选/model 可选；租户/系统由 Key 绑定值自动赋值，历史按 Key 隔离） |

`model` 可选值：`deepseek-v3.2` / `deepseek-v3.2-think` / `deepseek-v4-flash-0731`（不传则用配置默认模型）。

**SSE 响应顺序（JSON 事件，`type` 字段区分）：**

```json
{"type":"session","sessionId":"xxx"}
{"type":"sources","sources":[{...}]}
{"type":"reasoning","text":"思考片段"}
{"type":"content","text":"回答正文（Markdown）"}
```

### 对话记录（登录态）

| 方法     | 路径                                               | 权限   | 说明                                                            |
|----------|----------------------------------------------------|--------|-----------------------------------------------------------------|
| `POST`   | `/documents/v1/conversations`                     | 仅登录 | 手动保存对话记录（前端中断回答时调用）                          |
| `GET`    | `/documents/v1/sessions`                          | 仅登录 | 会话列表（按 userId + tenantCode + systemType，去重 sessionId） |
| `GET`    | `/documents/v1/conversations/session`             | 仅登录 | 按 sessionId 查询完整多轮对话                                   |
| `GET`    | `/documents/v1/conversations`                     | 仅登录 | 按用户查询对话记录                                              |
| `PUT`    | `/documents/v1/conversations/session/{sessionId}/title` | 仅登录 | 更新会话标题（Body: {"title":"新标题"}）                  |
| `DELETE` | `/documents/v1/conversations/session/{sessionId}` | 仅登录 | 删除单个会话（含所有对话记录）                                  |
| `DELETE` | `/documents/v1/conversations/batch`               | 仅登录 | 批量删除会话（Body: sessionId 数组）                            |

### 对话记录（API Key 专属，请求头必须携带 Key，仅操作本 Key 创建的记录）

| 方法     | 路径                                               | 权限      | 说明                                                            |
|----------|----------------------------------------------------|-----------|-----------------------------------------------------------------|
| `GET`    | `/keys/v1/conversations`                         | 仅 API Key | 会话列表（按 apikey + userId + tenantCode + systemType 搜索，后三者可选；标准 Pageable 分页） |
| `GET`    | `/keys/v1`                                       | 仅 API Key | 会话列表（简版，与 /keys/v1/conversations 等价）               |
| `GET`    | `/keys/v1/session`                               | 仅 API Key | 按 sessionId 查询消息（仅本 Key 创建的，否则 404）              |
| `DELETE` | `/keys/v1/session/{sessionId}`                   | 仅 API Key | 删除单个会话（仅限本 Key 创建的，否则 404）                     |
| `DELETE` | `/keys/v1/batch`                                 | 仅 API Key | 批量删除（Body: sessionId 数组，仅删本 Key 的，返回实际删除数） |

### 知识图谱

| 方法  | 路径                   | 权限   | 说明                                                         |
|-------|------------------------|--------|--------------------------------------------------------------|
| `GET` | `/documents/v1/graph` | 仅登录 | 获取图谱数据（tenantCode + systemType + limit，默认200节点；租户 0 查所有关系） |

---

## 模型选择

AI 对话接口支持在 `deepseek-v3.2`、`deepseek-v3.2-think`、`deepseek-v4-flash-0731` 三个模型间切换：

| 模型 | 特点 | 默认 |
|------|------|------|
| `deepseek-v3.2` | 通用对话，响应快，性价比高 | 前端下拉默认 |
| `deepseek-v3.2-think` | 深度思考模式，适合复杂推理问题 | 配置默认（`AI_CHAT_MODEL`） |
| `deepseek-v4-flash-0731` | 轻量快速版 | - |

- **前端**：对话输入框下方下拉框直接切换，下拉选项来自 `GET /ai/v1/provider`（按当前启用的 AI 平台下发，避免选到不存在的模型），随请求提交 `model` 参数
- **后端**：`POST /documents/v1/query` 与 `POST /keys/v1/chat` 的 `model` 字段覆盖默认配置；不传则用 `AI_CHAT_MODEL`
- **注意**：三个模型均需在千帆控制台**手动开通**后才可使用，未开通报 `401 The model does not exist or you do not have access to it.`

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

### 向量库配置

Neo4jVectorStore 使用 label=`DocumentChunk`，index-name=`knowledge_vector_index`，距离类型 cosine，维度 **1024**
（千帆 `bge-large-zh`）。细粒度切片 ID 与向量节点相同，禁止使用 `saveAll`（会覆盖 embedding），必须通过 Cypher UPDATE 更新属性。

> **从智谱（2048 维）迁移千帆（1024 维）时必须重建索引：**
> ```cypher
> DROP INDEX knowledge_vector_index IF EXISTS
> ```
> 重启应用后（`initialize-schema=true`）自动按 1024 维重建；**存量文档需重新上传/向量化**，否则检索失效。

### Neo4j 配置

Spring Boot 4.x 的 `spring-boot-starter-neo4j` 仅自动配置 Driver，需手动声明 `Neo4jTemplate` / `MappingContext`（见
`Neo4jConfiguration.java`）。启动时自动创建 `KnowledgeEntity.name` 唯一约束，防止并行实体抽取竞态。

### 认证数据表

启动时通过 `schema.sql`（`spring.sql.init.mode=always`）自动创建 `api_key` 等业务表，无需手动建表：

| 字段          | 说明                         |
|---------------|------------------------------|
| `name`        | 备注（必填）                 |
| `key_prefix`  | 前缀掩码（如 `sk-Ab3Xy7...`）|
| `key_hash`    | SHA-256 哈希（CHAR(64) 唯一）|
| `tenant_code` | 绑定租户编码（必填）         |
| `system_type` | 绑定系统类型（必填）         |
| `enabled`     | 是否启用                     |
| `created_by`  | 创建人（超管用户名）         |
| `created_time`| 创建时间                     |

### Jackson 3.x 配置

Spring Boot 4.1.1 使用 Jackson 3.x（包名 `tools.jackson`），通过 `JsonMapperBuilderCustomizer` 注册自定义
`LocalDateTimeSerializer`，全局格式 `yyyy-MM-dd HH:mm:ss`。

### 时区配置

启动类 `MotcsGraphragApplication.main()` 最前面设置 `TimeZone.setDefault(Asia/Shanghai)`，确保容器内时间正确。

---

## 常见问题

**Q: 启动报 `The client is unauthorized due to authentication failure`**
A: Neo4j 账号密码不正确，检查 `NEO4J_AUTH_USERNAME` / `NEO4J_AUTH_PASSWORD` 环境变量。

**Q: 上传 docx 报 `NoSuchMethodError: BoundedInputStream.builder()`**
A: POI 5.4.0 需要 commons-io 2.16+，项目已升级到 2.18.0。

**Q: 上传 docx 报"文档未提取到文字内容"**
A: 多为表格型文档。已修复：Word 转换按文档顺序遍历段落 + 表格（含嵌套表格），内容在表格中的工作类 docx 可正常提取。若仍报错，检查文件是否为扫描件/加密文档。

**Q: AI 回答报 429 限流**
A: 大文档实体抽取已做批量合并 + 并发限制（最多200片、并发2、批次间隔1.5s），多跳召回限制30条。如仍触发，请降低
`AI_CHAT_TEMPERATURE` 或升级 API 配额。

**Q: AI 调用报 401 `The model does not exist or you do not have access to it.`**
A: 模型未在千帆控制台开通（免费模型也需手动开通）。到千帆控制台"模型广场"开通对应模型后重试；确认
`AI_API_KEY` 为 `bce-v3/...` 开头的千帆 Key。

**Q: 图谱构建报 `TimeoutException`，实体抽取超时**
A: 批量图谱构建的超时预算已与 `AI_TIMEOUT` 对齐（每批最多 300 秒）。思考模型（`deepseek-v3.2-think`）响应较慢属正常；
若单次超过 5 分钟仍超时，检查千帆 API 负载或换用 `deepseek-v3.2`。

**Q: 访问接口返回 401 / 浏览器弹出 Basic Auth 登录框**
A: 未登录或登录已过期。系统已配置自定义 JSON 401 入口（不会弹浏览器原生框），请在管理界面重新登录；接口调用请携带
`Authorization: Bearer sk-...` 或 `X-API-Key: sk-...`。

**Q: API Key 报 403 无权限访问**
A: API Key 仅能访问 AI 对话接口（`/documents/v1/query`、`/keys/v1/chat`）与 `/keys/v1/**` 专属历史接口，
访问上传、列表、图谱等管理业务接口会被拒绝（仅超管登录可用）。

**Q: 登录后 POST 接口报 403 `CSRF 校验失败`**
A: 登录态的 POST/PUT/DELETE 请求必须在请求头携带 `X-CSRF-TOKEN`（值等于 cookie 中的 `XSRF-TOKEN`）。
API Key 请求（`Bearer` / `X-API-Key`）与 `/keys/v1/**` 已豁免 CSRF，无需携带。

**Q: 文档一直显示"处理中"**
A: 图谱构建为异步操作，前端每30秒轮询状态。若长时间未完成，检查日志中实体抽取是否超时或 API 密钥是否过期。

**Q: 千帆向量化报批量提交超限**
A: `bge-large-zh` 单次约 16 条文本上限。若大文档整体批量向量化报错，需在 `DocumentService` 中按约 16 条分批提交。

**Q: Docker 容器时间比北京时间少8小时**
A: 已在启动类固定 JVM 时区为 Asia/Shanghai，确保使用最新镜像。

---

## 项目结构

```
motcs-graphrag/
├── src/main/java/com/motcs/
│   ├── MotcsGraphragApplication.java        # 启动类（时区配置）
│   ├── commons/                             # 公共工具
│   │   ├── ContextUtil.java                 # 工具类（ObjectMapper / 条件构建 / 分页 SQL）
│   │   ├── annotation/RestServerException.java
│   │   ├── converters/                      # R2DBC / Jackson 类型转换器
│   │   └── utils/
│   │       ├── AnyDocConverterUtil.java     # 多格式转 Markdown（PDF/Word含表格/Excel/PPT）
│   │       ├── EnterpriseChunker.java       # 企业级双层切分器
│   │       ├── ByteArrayMultipartFile.java  # WebFlux 文件适配
│   │       ├── FileUtils.java               # 文件工具（页码提取/范围解析）
│   │       ├── ParameterSql.java            # 参数化 SQL 工具
│   │       └── Utils.java                   # 通用工具（格式校验/缓冲区合并）
│   ├── config/
│   │   ├── AiConfiguration.java             # AI 配置（ChatClient / EmbeddingModel）
│   │   ├── Neo4jConfiguration.java          # Neo4j 手动配置（Template / Mapping / 约束）
│   │   ├── SecurityConfiguration.java       # 安全配置（双通道认证 / CSRF / 权限矩阵 / JSON 401/403）
│   │   └── WebFluxConfiguration.java        # WebFlux 配置（Pageable 参数解析等）
│   └── core/                                # 业务核心（按模块分包）
│       ├── auth/
│       │   ├── AuthController.java          # 登录/登出/me/API Key 管理接口
│       │   ├── csrf/LoginIssuedCsrfTokenRepository.java  # CSRF 双提交 Cookie 仓库
│       │   ├── keys/
│       │   │   ├── ApiKey.java              # 实体（只存哈希 + 前缀掩码 + 租户/系统）
│       │   │   ├── ApiKeyInfo.java          # 生成结果（含明文，仅一次）
│       │   │   ├── ApiKeyRepository.java    # R2DBC 仓库
│       │   │   ├── ApiKeyService.java       # Key 生成/校验/列表/删除
│       │   │   └── ApiKeyController.java    # /keys/v1/conversations（Key 专属历史）
│       │   └── token/
│       │       ├── AuthenticationToken.java # 登录响应（token/expires/lastAccessTime）
│       │       └── TokenStore.java          # x-token 会话存储（滑动过期）
│       ├── chat/
│       │   └── ApiChatController.java       # POST /keys/v1/chat（Key 专属对话，SSE）
│       ├── document/
│       │   ├── DocumentController.java      # 文档/问答/对话/图谱接口
│       │   ├── DocumentService.java         # 文档服务（异步入库/级联删除/统计）
│       │   └── DocumentUploadRequest / DocumentRequest / DocumentResponse / DocumentStatsResponse / DocumentSummary
│       ├── knowledge/
│       │   ├── KnowledgeEntity.java / KnowledgeEntityRepository.java   # 图谱实体
│       │   ├── chunk/DocumentChunk.java / DocumentChunkRepository.java # 分片（租户隔离 Cypher）
│       │   ├── graph/GraphRagRequest.java / GraphRagResult.java / GraphRagService.java  # GraphRAG 核心
│       │   └── record/ChatMessage.java / ChatMessageRepository.java / ChatSessionSummary.java / ChatSessionSummaryRepository.java
│       ├── provider/AiProviderController.java   # GET /ai/v1/provider（AI 平台探测）
│       └── request/ SessionRequest.java / FileUploadRequest.java / ApiKeyRequest.java
├── src/main/resources/
│   ├── application.yaml                    # 基础配置（Jackson/虚拟线程/HTTP2/认证账号）
│   ├── application-baidu.yaml              # 百度千帆专项配置（--spring.profiles.active=baidu）
│   ├── db/schema.sql                       # 建表脚本（api_key 等，启动自动执行）
│   └── static/                             # 前端单页应用
│       ├── index.html                      # 主页面（登录/问答/文档/图谱/API Key 管理 + 模型下拉）
│       ├── css/style.css                   # 深色主题样式
│       ├── js/app.js                       # 前端交互逻辑（401 拦截/登录/CSRF/API Key 管理）
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
| [Spring Security](https://spring.io/projects/spring-security)                        | 7.1     | 认证与鉴权（登录 + API Key + CSRF）     | Apache 2.0 |
| [Spring AI](https://spring.io/projects/spring-ai)                                    | 2.0.1   | AI 抽象层（向量存储/对话模型）          | Apache 2.0 |
| [Project Reactor](https://projectreactor.io/)                                        | -       | 响应式编程库                            | Apache 2.0 |
| [Lombok](https://projectlombok.org/)                                                 | 1.18.46 | 编译时代码生成                          | MIT        |
| [Jackson 3.x](https://github.com/FasterXML/jackson)                                  | 2.21.+  | JSON 序列化/反序列化                    | Apache 2.0 |

### 数据库与驱动

| 项目                                                              | 版本         | 用途                          | 许可证       |
|-------------------------------------------------------------------|--------------|-------------------------------|--------------|
| [Neo4j](https://neo4j.com/)                                       | 5.x / 2026.x | 图数据库 + 向量数据库         | GPLv3 / 商业 |
| [Neo4j Java Driver](https://github.com/neo4j/neo4j-java-driver)   | 6.1.0        | Neo4j 官方驱动                | Apache 2.0   |
| [Spring Data Neo4j](https://spring.io/projects/spring-data-neo4j) | 8.1.1        | Neo4j ORM / Repository        | Apache 2.0   |
| [MySQL](https://www.mysql.com/) + R2DBC                           | 8.x          | 认证/业务数据存储（api_key、chat_message） | GPLv2       |

### 文档处理

| 项目                                                                               | 版本   | 用途                    | 许可证     |
|------------------------------------------------------------------------------------|--------|-------------------------|------------|
| [Apache PDFBox](https://pdfbox.apache.org/)                                        | 2.0.29 | PDF 文本提取            | Apache 2.0 |
| [Apache POI](https://poi.apache.org/)                                              | 5.4.0  | Word/Excel/PPT 文档解析 | Apache 2.0 |
| [Apache Commons IO](https://commons.apache.org/proper/commons-io/)                 | 2.18.0 | 文件 I/O 工具           | Apache 2.0 |
| [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) | 1.6.0  | 文件上传处理            | Apache 2.0 |

### AI 服务

| 项目                                               | 用途                                                             |
|----------------------------------------------------|------------------------------------------------------------------|
| [百度千帆 (Baidu Qianfan)](https://qianfan.baidubce.com/) | deepseek-v3.2 系列对话模型 + bge-large-zh 向量模型（OpenAI 兼容接口） |
| [智谱 AI (Zhipu AI)](https://open.bigmodel.cn/)    | 兼容备选（GLM 系列 + embedding，OpenAI 兼容接口）                |

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

完整协议见 [LICENSE](LICENSE)。
