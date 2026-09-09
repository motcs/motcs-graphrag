# Motcs Graphrag — GraphRAG Multi-Hop Intelligent Search System

> [中文](./README.md) | **English**

An enterprise-grade document knowledge base and multi-hop intelligent Q&A system built on **Spring Boot 4.1.1 + Neo4j + MySQL + Spring AI 2.0.1**. Built with a pure WebFlux reactive architecture, supporting async document ingestion, enterprise-grade dual-layer chunking, vector retrieval + knowledge graph multi-hop reasoning, streaming conversations, and citation traceability. Ships with **admin login + API Key authentication** and tiered API authorization.

---

## Features

### Intelligent Q&A

- Multi-turn conversations with automatic context via `sessionId`
- **Selectable models**: dropdown below the input toggles `deepseek-v3.2` / `deepseek-v3.2-think` / `deepseek-v4-flash-0731` (options are served by `GET /ai/v1/provider` based on the active AI platform, preventing invalid selections)
- SSE streaming output (JSON events: session / sources / reasoning / content) with real-time Markdown rendering
- Dual-path retrieval: vector search + Neo4j multi-hop graph recall
- Each answer displays cited knowledge chunks, clickable to view source (with page navigation)
- Conversation history persisted (Neo4j + MySQL), isolated by user/tenant/system; API Key conversations are isolated per key
- Export conversations as Markdown, single/batch deletion
- Clear prompts when no results found (no docs / processing / mismatch)

### Document Management

- Supports 10 formats: PDF / Word / Excel / PPT / TXT / CSV / Markdown and more
- **Table-aware Word conversion**: iterates paragraphs and tables (including nested tables) in document order, so table-heavy `.docx` files are fully extracted
- Immediate response on upload; graph construction runs asynchronously
- Real-time status polling: Processing → Completed / Failed
- Enterprise dual-layer chunking (fine-grained retrieval + coarse-grained reasoning) with page numbers and title hierarchy
- Cascade deletion by docCode: chunks, vectors, entities, relations, source files
- Deleted documents are marked in conversation citations to ensure data consistency
- Multi-tenant + multi-system isolation; docs uploaded by the admin (tenant `0`) are open to all tenants; users can only delete their own uploads
- Lightweight stats API (aggregated query, scales to millions of documents)
- Failed uploads support retry with auto-filled form

### Knowledge Graph

- Entity extraction and relation building with force-directed visualization
- Auto-stops physics simulation after stabilization, supports re-layout
- Tenant/system filtering: tenant `0` returns all relations; a specific tenant sees only relations of «its own + global tenant (0)» documents
- Node count limited to avoid performance issues

### Authentication & Authorization

> **Tenant semantics**: tenant `0` is the admin global tenant.
> - **Document/chat context**: tenant `0` queries **skip tenant filtering and search all documents**; a specific tenant searches «its own documents + tenant-0 global documents», with its own content taking precedence.
> - **Conversation records**: always isolated by tenant — the admin (tenant `0`) only sees their own records; API Key conversations are isolated per key.
> - **API Key**: cannot be bound to tenant `0`.

- **Admin login**: single management account (env-configured). Issues an expiring `x-token` on success; subsequent requests carry the `x-token` header
- **API Key auth**: admins generate keys for manual distribution; callers use the key to access AI chat endpoints. **Each key binds a tenant + system type**, and chat automatically searches that tenant's knowledge
- **API Key chat endpoint**: `POST /keys/v1/chat` — callers only send question / userId / sessionId; tenant & system are resolved from the key binding
- Four authorization tiers: public / admin-only / login-or-API-Key / API-Key-only (see [Authentication & Authorization](#authentication--authorization))
- OpenAI-style key design: only SHA-256 hashes stored, plaintext shown once at creation, note/tenant/system required

### Architecture

- Pure Spring WebFlux reactive, virtual threads + Reactor
- Neo4j doubles as vector store and graph database; MySQL (R2DBC) stores API keys, chat messages, etc.
- Spring Security (WebFlux): session login + stateless API Key dual channel
- CSRF double-submit cookie protection (POST only; API Key requests exempt)
- Jib containerization, environment-variable-driven configuration, no rebuild needed
- Jackson 3.x global date formatting, JVM timezone fixed to Asia/Shanghai

---

## Tech Stack

| Category | Technology |
|----------|-----------|
| Framework | Spring Boot 4.1.1, Spring WebFlux, Spring Security 7.1 |
| AI | Spring AI 2.0.1, OpenAI-compatible API (Baidu Qianfan, Zhipu optional) |
| Database | Neo4j (vector + graph unified), MySQL 8.x (R2DBC) |
| Document Processing | Apache PDFBox 2.0.29, Apache POI 5.4.0, commons-io 2.18.0 |
| Build | Gradle 9.7.0, Jib 3.5.4 |
| Language | Java 26 |
| Frontend | Vanilla HTML + Tailwind CSS + marked.js + vis-network |

---

## Quick Start

### Prerequisites

- JDK 26+
- Neo4j 5.x / 2026.x (with vector index enabled)
- MySQL 8.x (R2DBC; tables such as `api_key` are created automatically at startup)
- Baidu Qianfan API Key (starts with `bce-v3/...`; **manually enable** the models you need in the console)

### Local Development

1. **Start Neo4j and MySQL**

   See [NEO4J.md](./NEO4J.md) for Neo4j installation and setup; create the MySQL database (default name `motcs`).

2. **Configure environment variables** (or edit `application-baidu.yaml`)

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
   # Baidu Qianfan
   export AI_API_KEY=your_qianfan_api_key
   export AI_BASE_URL=https://qianfan.baidubce.com/v2
   # Admin account (must change in production)
   export AUTH_USERNAME=admin
   export AUTH_PASSWORD=your_strong_password
   ```

3. **Run the application**

   ```bash
   ./gradlew bootRun
   ```

   Or with an explicit profile:

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=baidu'
   ```

4. **Open the UI**

   Visit `http://localhost:8080/` in your browser and log in with the admin account.

---

## Docker Deployment

### Build Image

```bash
# Set registry credentials
export DOCKER_USERNAME=your_username
export DOCKER_PASSWORD=your_password
export DOCKER_URL=your-registry.com

# Build and push
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

## Environment Variables

### Core

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | Server port |
| `SPRING_PROFILES_ACTIVE` | `prod` | Active profile (`baidu` for Qianfan) |
| `NEO4J_URI` | `bolt://127.0.0.1:7687` | Neo4j connection URI |
| `NEO4J_AUTH_USERNAME` | `neo4j` | Neo4j username |
| `NEO4J_AUTH_PASSWORD` | - | Neo4j password |
| `MYSQL_HOST` | `127.0.0.1` | MySQL host |
| `MYSQL_PORT` | `3306` | MySQL port |
| `MYSQL_DATABASE` | `motcs` | MySQL database |
| `MYSQL_USERNAME` | `root` | MySQL username |
| `MYSQL_PASSWORD` | - | MySQL password |

### AI (Baidu Qianfan)

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_BASE_URL` | `https://qianfan.baidubce.com/v2` | Qianfan OpenAI-compatible endpoint |
| `AI_API_KEY` | - | Qianfan API key (`bce-v3/...`) |
| `AI_CHAT_MODEL` | `deepseek-v3.2-think` | Chat model (see [Model Selection](#model-selection)) |
| `AI_CHAT_TEMPERATURE` | `0.1` | Chat temperature |
| `AI_TIMEOUT` | `300s` | AI request timeout (keep 5 min for thinking models) |
| `AI_EMBEDDING_MODEL` | `bge-large-zh` | Embedding model (1024-dim, Chinese) |
| `AI_EMBEDDING_DIMENSION` | `1024` | Embedding dimension (must match the model) |
| `AI_PROVIDER` | `baidu` | Active AI platform id (drives the model dropdown) |
| `AI_CHAT_MODELS` | `deepseek-v3.2,deepseek-v3.2-think,deepseek-v4-flash-0731` | Comma-separated selectable models |

> Qianfan `bge-large-zh` is **1024-dimensional** with a ~**16 texts/request** limit. When migrating from Zhipu
> `embedding-3` (2048-dim), rebuild the vector index (see [Vector Store](#vector-store)).

### Authentication

| Variable | Default | Description |
|----------|---------|-------------|
| `AUTH_USERNAME` | `xxhzj` (see application-baidu.yaml) | Admin username — override in production |
| `AUTH_PASSWORD` | - | Admin password — override in production |
| `AUTH_TOKEN_TTL` | `7200` | x-token TTL (seconds); sliding expiry on each valid use |
| `API_KEY_LEN` | `40` | Random part length of API keys (besides `sk-` prefix) |

### File

| Variable | Default | Description |
|----------|---------|-------------|
| `FILE_UPLOAD_DIR` | `./uploads` | File upload directory |
| `FILE_MAX_SIZE` | `52428800` | Max file size (50MB) |
| `SUPPORTED_FORMATS` | `pdf,doc,docx,xls,xlsx,ppt,pptx,txt,csv,md` | Supported formats (comma-separated) |

### Image Build (Jib)

| Variable | Description |
|----------|-------------|
| `DOCKER_USERNAME` | Registry username |
| `DOCKER_PASSWORD` | Registry password |
| `DOCKER_URL` | Registry URL |

---

## Authentication & Authorization

> **Tenant semantics**: tenant `0` is the admin global tenant.
> - **Document/chat context**: tenant `0` queries **skip tenant filtering and search all documents**; a specific tenant searches «its own documents + tenant-0 global documents», with its own content taking precedence.
> - **Conversation records**: always isolated by tenant — the admin (tenant `0`) only sees their own records; API Key conversations are isolated per key.
> - **API Key**: cannot be bound to tenant `0`.

The system uses **Spring Security (WebFlux)** dual-channel authentication: **admin session login** and **stateless API Key auth**.

### 1. Authentication Methods

| Method | Description |
|--------|-------------|
| Admin login | `POST /auth/v1/login` with **HTTP Basic Auth** (`Authorization: Basic base64(username:password)`), verified by Spring Security Basic filter (`AUTH_USERNAME` / `AUTH_PASSWORD`). Returns an **x-token**; subsequent requests carry the `x-token` header |
| API Key | `Authorization: Bearer sk-...` or `X-API-Key: sk-...` header; stateless validation (SHA-256 hash comparison per request). For machines / third-party callers |

### 2. API Key Design (OpenAI-style)

- **Format**: `sk-` + 40 random chars (length configurable via `API_KEY_LEN`; confusing chars `0O1lI` excluded)
- **Storage**: only the SHA-256 hash and a prefix mask (e.g. `sk-Ab3Xy7...`) are stored; **the plaintext is returned once at creation** — regenerate if lost
- **Note required**: `name` is mandatory, empty returns `400`
- **Tenant/system required**: must bind `tenantCode` and `systemType` at creation (used for chat/doc ownership); **tenant `0` is forbidden** (admin global tenant), returns `400`
- **Authorization**: API Key identity is `ROLE_API_KEY` — it can access the AI chat endpoints and `/keys/v1/**`; admin endpoints return `403`
- **Management**: only the admin can generate / list / delete keys (deleting a key instantly invalidates requests carrying it)

### 3. Endpoint Authorization Matrix

| Path | Access |
|------|--------|
| `/`, `/index.html`, `/css/**`, `/js/**`, `/img/**`, favicon, Swagger (`/v3/api-docs/**`, `/swagger-ui/**`, `/webjars/**`) | **Public** |
| `POST /auth/v1/login` | **Public** |
| `GET /documents/v1/health` (health check) | **Public** |
| `GET /ai/v1/provider` (AI platform probe, drives model dropdown before login) | **Public** |
| `POST /auth/v1/logout`, `GET /auth/v1/me` | **Admin only** |
| `GET/POST /auth/v1/api-keys`, `DELETE /auth/v1/api-keys/{id}` (key management) | **Admin only** |
| **`POST /documents/v1/query` (AI chat)** | **Login OR valid API Key** |
| **`/keys/v1/**` (API-Key-only: `/chat` + `/conversations`)** | **API Key only** (admin login gets 403) |
| All other business endpoints (upload/list/delete/stats/graph/sessions) | **Admin only** |

### 4. Usage Examples

**Admin login (Basic Auth → x-token)**

```bash
curl -X POST http://localhost:8080/auth/v1/login \
  -H "Authorization: Basic $(echo -n 'admin:your_password' | base64)"
```

> PowerShell: `[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('admin:your_password'))`

Returns: `{"token":"xxx","expires":7200,"lastAccessTime":...}`. Carry `x-token` in subsequent requests.
Tokens are registered in TokenStore with a TTL (default 2h, `AUTH_TOKEN_TTL`), sliding-expired on each valid use.

**CSRF (double-submit cookie, POST only)**: the backend issues `Set-Cookie: XSRF-TOKEN=...` (httpOnly=false). The frontend reads the cookie and sends `X-CSRF-TOKEN` on authenticated **POST** requests; the backend compares header vs cookie. **GET and other methods are not checked.** Exempt: login, `/keys/v1/**`, and **API Key requests (`Authorization: Bearer` / `X-API-Key`)**.

**AI chat with an API Key (SSE)**

```bash
curl -N -X POST http://localhost:8080/documents/v1/query \
  -H "Authorization: Bearer sk-Ab3Xy7..." \
  -H "Content-Type: application/json" \
  -d '{"question":"What is multi-hop retrieval?","userId":"user0001","tenantCode":"test-tenant","systemType":"docs"}'
```

**API-Key-only chat (tenant/system auto-resolved from the key; just send question / userId / sessionId)**

```bash
curl -N -X POST http://localhost:8080/keys/v1/chat \
  -H "X-API-Key: sk-Ab3Xy7..." \
  -H "Content-Type: application/json" \
  -d '{"question":"What was discussed in the meeting?","userId":"test0101","sessionId":"sess_001"}'
```

**Create an API Key (admin login; note/tenant/system required)**

```bash
curl -X POST http://localhost:8080/auth/v1/api-keys \
  -H "Content-Type: application/json" \
  -H "x-token: tok-xxx..." \
  -d '{"name":"Third-party integration","tenantCode":"410725","systemType":"congress"}'
```

**Unauthenticated business call** → `401` `{"code":401,"message":"未登录或登录已过期"}`
**API Key accessing admin endpoints** → `403` `{"code":403,"message":"无权限访问"}`
**Admin login accessing `/keys/v1/**`** → `403` (API Key only)

---

## API Reference

> All endpoints require authentication (login or API Key) unless marked "Public" (see the [matrix](#3-endpoint-authorization-matrix)).

### Auth & API Key Management

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `POST` | `/auth/v1/login` | Public | Admin login (Basic Auth) |
| `POST` | `/auth/v1/logout` | Admin | Logout (invalidates x-token) |
| `GET` | `/auth/v1/me` | Admin | Current login state |
| `GET` | `/auth/v1/api-keys` | Admin | Key list (masked only) |
| `POST` | `/auth/v1/api-keys` | Admin | Create key (Body: name/**tenantCode**/**systemType** required; plaintext returned once) |
| `DELETE` | `/auth/v1/api-keys/{id}` | Admin | Delete key (immediately invalid) |

### AI Platform Probe

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `GET` | `/ai/v1/provider` | Public | Active AI platform (provider/providerName) + model list (chatModels) driving the frontend dropdown |

### Document Management

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `POST` | `/documents/v1/upload` | Admin | Upload (multipart/form-data: file/title/description/docCode/tenantCode/systemType/userId) |
| `POST` | `/documents/v1/upload/url` | Admin | Upload via URL (Body: FileUploadRequest) |
| `GET` | `/documents/v1/list` | Admin | List documents (tenantCode + systemType; tenant 0 → all) |
| `DELETE` | `/documents/v1/docCode/{docCode}` | Admin | Delete by docCode (cascade: chunks/vectors/entities/relations/files) |
| `GET` | `/documents/v1/stats` | Admin | Document statistics (lightweight aggregation) |
| `GET` | `/documents/v1/health` | Public | Health check |

### Intelligent Q&A

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `POST` | `/documents/v1/query` | **Login OR API Key** | GraphRAG Q&A (SSE; params: question/userId/sessionId/tenantCode/systemType/model optional) |
| `POST` | `/keys/v1/chat` | **API Key only** | API-Key-only Q&A (SSE; params: question/userId/sessionId optional/model optional; tenant/system from key binding; history isolated per key) |

`model` values: `deepseek-v3.2` / `deepseek-v3.2-think` / `deepseek-v4-flash-0731` (defaults to the configured model).

**SSE response order (JSON events, distinguished by `type`):**

```json
{"type":"session","sessionId":"xxx"}
{"type":"sources","sources":[{...}]}
{"type":"reasoning","text":"thinking..."}
{"type":"content","text":"answer (Markdown)"}
```

### Conversation History (Admin session)

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `POST` | `/documents/v1/conversations` | Admin | Manually save a conversation (frontend interruption) |
| `GET` | `/documents/v1/sessions` | Admin | Session list (userId + tenantCode + systemType, distinct sessionId) |
| `GET` | `/documents/v1/conversations/session` | Admin | Full multi-turn conversation by sessionId |
| `GET` | `/documents/v1/conversations` | Admin | Conversation records by user |
| `PUT` | `/documents/v1/conversations/session/{sessionId}/title` | Admin | Update session title (Body: {"title":"..."}) |
| `DELETE` | `/documents/v1/conversations/session/{sessionId}` | Admin | Delete a session (all its records) |
| `DELETE` | `/documents/v1/conversations/batch` | Admin | Batch delete (Body: sessionId array) |

### Conversation History (API-Key-only; must carry the key; only records created by this key)

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `GET` | `/keys/v1/conversations` | API Key only | Session list (apikey + userId + tenantCode + systemType; last three optional) |
| `GET` | `/keys/v1/conversations/session` | API Key only | Messages by sessionId (404 if not owned by this key) |
| `DELETE` | `/keys/v1/conversations/session/{sessionId}` | API Key only | Delete a session (only if owned by this key, else 404) |
| `DELETE` | `/keys/v1/conversations/batch` | API Key only | Batch delete (Body: sessionId array; only this key's sessions; returns actual count) |

### Knowledge Graph

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| `GET` | `/documents/v1/graph` | Admin | Graph data (tenantCode + systemType + limit, default 200; tenant 0 → all relations) |

---

## Model Selection

The chat endpoints support switching between `deepseek-v3.2`, `deepseek-v3.2-think` and `deepseek-v4-flash-0731`:

| Model | Characteristics | Default |
|-------|-----------------|---------|
| `deepseek-v3.2` | General chat, fast, cost-effective | Frontend dropdown default |
| `deepseek-v3.2-think` | Deep reasoning, for complex questions | Config default (`AI_CHAT_MODEL`) |
| `deepseek-v4-flash-0731` | Lightweight fast variant | - |

- **Frontend**: dropdown below the input; options come from `GET /ai/v1/provider` (per active platform); the selection is submitted as `model`
- **Backend**: the `model` field of `/documents/v1/query` and `/keys/v1/chat` overrides the default; empty uses `AI_CHAT_MODEL`
- **Note**: all three models must be **manually enabled** in the Qianfan console; otherwise you get `401 The model does not exist or you do not have access to it.`

---

## Enterprise-Grade Chunking

The system uses a dual-layer chunking architecture optimized for multi-hop RAG:

| Layer | Purpose | Token Range | Overlap |
|-------|---------|-------------|---------|
| Fine-grained | Vector retrieval | 384 ~ 512 | 25% |
| Coarse-grained | Multi-hop reasoning | 1024 ~ 1536 | 20% |

**Key rules:**
- Semantic structure first — never hard-cut complete sentences/clauses/lists/tables
- Each chunk carries full title hierarchy and page range (supports cross-page `1-2`)
- Fine chunks bind to parent coarse chunks; context is auto-expanded after retrieval
- Metadata includes docId, chunkId, parentChunkId, prev/next links, titleHierarchy, tokenSize, etc.
- Large documents (>200 chunks) have rate-limited entity extraction to avoid AI API 429

---

## Configuration Notes

### Vector Store
Neo4jVectorStore uses label=`DocumentChunk`, index-name=`knowledge_vector_index`, cosine distance, **1024** dimensions (Qianfan `bge-large-zh`). Fine-grained chunk IDs match vector node IDs — `saveAll` must NOT be used (it overwrites embeddings); use Cypher UPDATE for property changes.

> **When migrating from Zhipu (2048-dim) to Qianfan (1024-dim), rebuild the index:**
> ```cypher
> DROP INDEX knowledge_vector_index IF EXISTS
> ```
> On restart (`initialize-schema=true`) it is rebuilt at 1024 dims; **existing documents must be re-uploaded/re-embedded**, otherwise retrieval fails.

### Neo4j
Spring Boot 4.x `spring-boot-starter-neo4j` only auto-configures the Driver. `Neo4jTemplate` / `MappingContext` must be declared manually (see `Neo4jConfiguration.java`). A unique constraint on `KnowledgeEntity.name` is created at startup to prevent race conditions in parallel entity extraction.

### Auth Data Tables
`schema.sql` (`spring.sql.init.mode=always`) creates business tables such as `api_key` automatically:

| Field | Description |
|-------|-------------|
| `name` | Note (required) |
| `key_prefix` | Prefix mask (e.g. `sk-Ab3Xy7...`) |
| `key_hash` | SHA-256 hash (CHAR(64) unique) |
| `tenant_code` | Bound tenant (required) |
| `system_type` | Bound system type (required) |
| `enabled` | Enabled flag |
| `created_by` | Creator (admin username) |
| `created_time` | Creation time |

### Jackson 3.x
Spring Boot 4.1.1 uses Jackson 3.x (package `tools.jackson`). A custom `LocalDateTimeSerializer` is registered via `JsonMapperBuilderCustomizer`, global format `yyyy-MM-dd HH:mm:ss`.

### Timezone
`TimeZone.setDefault(Asia/Shanghai)` is set at the very beginning of `MotcsGraphragApplication.main()` to ensure correct time inside containers.

---

## FAQ

**Q: Startup fails with `The client is unauthorized due to authentication failure`**
A: Incorrect Neo4j credentials. Check `NEO4J_AUTH_USERNAME` / `NEO4J_AUTH_PASSWORD`.

**Q: Uploading docx throws `NoSuchMethodError: BoundedInputStream.builder()`**
A: POI 5.4.0 requires commons-io 2.16+. The project uses 2.18.0.

**Q: Uploading docx reports "文档未提取到文字内容" (no text extracted)**
A: Usually a table-heavy document. Fixed: Word conversion now iterates paragraphs and tables (incl. nested) in document order. If it still fails, check for scanned/encrypted files.

**Q: AI answers return 429 rate limit**
A: Entity extraction already uses batching + concurrency limits (max 200 chunks, concurrency 2, 1.5s interval), multi-hop recall capped at 30. If still triggered, lower `AI_CHAT_TEMPERATURE` or upgrade quota.

**Q: AI call returns 401 `The model does not exist or you do not have access to it.`**
A: The model is not enabled in the Qianfan console (free models also require manual enabling). Enable it under "Model Square" and verify `AI_API_KEY` starts with `bce-v3/`.

**Q: Graph construction fails with `TimeoutException` (entity extraction timeout)**
A: The batch timeout budget now matches `AI_TIMEOUT` (300s per batch). Thinking models (`deepseek-v3.2-think`) respond slowly by design; if a single call exceeds ~5 min, check Qianfan load or switch to `deepseek-v3.2`.

**Q: 401 on business endpoints / browser Basic Auth popup**
A: Not logged in or session expired. The system uses custom JSON 401 (no native popup). Re-login in the UI, or send `Authorization: Bearer sk-...` / `X-API-Key: sk-...`.

**Q: API Key gets 403 无权限访问 (no permission)**
A: API Keys can only access the AI chat endpoints (`/documents/v1/query`, `/keys/v1/chat`) and `/keys/v1/**` history endpoints. Upload/list/graph/session management are admin-only.

**Q: 403 `CSRF 校验失败` on POST after login**
A: Authenticated POST/PUT/DELETE requests must send `X-CSRF-TOKEN` equal to the `XSRF-TOKEN` cookie. API Key requests and `/keys/v1/**` are CSRF-exempt.

**Q: Document stays "Processing" forever**
A: Graph construction is async; the frontend polls every 30s. If stuck, check logs for entity extraction timeout or an expired API key.

**Q: Qianfan embedding batch limit exceeded**
A: `bge-large-zh` allows ~16 texts per request. If a large document fails in one batch, split submission into ~16-chunk batches in `DocumentService`.

**Q: Docker container time is 8 hours behind Beijing**
A: JVM timezone is fixed to Asia/Shanghai in the startup class. Ensure you're using the latest image.

---

## Project Structure

```
motcs-graphrag/
├── src/main/java/com/motcs/
│   ├── MotcsGraphragApplication.java        # Entry point (timezone config)
│   ├── commons/                             # Common utilities
│   │   ├── ContextUtil.java                 # ObjectMapper / criteria / paged SQL helpers
│   │   ├── annotation/RestServerException.java
│   │   ├── converters/                      # R2DBC / Jackson type converters
│   │   └── utils/
│   │       ├── AnyDocConverterUtil.java     # Multi-format → Markdown (PDF/Word+tables/Excel/PPT)
│   │       ├── EnterpriseChunker.java       # Enterprise dual-layer chunker
│   │       ├── ByteArrayMultipartFile.java  # WebFlux file adapter
│   │       ├── FileUtils.java               # Page extraction / range parsing
│   │       ├── ParameterSql.java            # Parameterized SQL helper
│   │       └── Utils.java                   # Format checks / buffer merge
│   ├── config/
│   │   ├── AiConfiguration.java             # ChatClient / EmbeddingModel beans
│   │   ├── Neo4jConfiguration.java          # Neo4j manual config (Template/Mapping/constraint)
│   │   ├── SecurityConfiguration.java       # Dual-channel auth / CSRF / matrix / JSON 401/403
│   │   └── WebFluxConfiguration.java        # WebFlux config (Pageable resolver, etc.)
│   └── core/                                # Business modules
│       ├── auth/
│       │   ├── AuthController.java          # login/logout/me/API-Key management
│       │   ├── csrf/LoginIssuedCsrfTokenRepository.java
│       │   ├── keys/
│       │   │   ├── ApiKey.java              # Entity (hash + prefix mask + tenant/system)
│       │   │   ├── ApiKeyInfo.java          # Creation result (plaintext, once)
│       │   │   ├── ApiKeyRepository.java    # R2DBC repository
│       │   │   ├── ApiKeyService.java       # Generate/validate/list/delete
│       │   │   └── ApiKeyController.java    # /keys/v1/conversations (key-only history)
│       │   └── token/
│       │       ├── AuthenticationToken.java # Login response (token/expires/lastAccessTime)
│       │       └── TokenStore.java          # x-token session store (sliding expiry)
│       ├── chat/
│       │   └── ApiChatController.java       # POST /keys/v1/chat (key-only chat, SSE)
│       ├── document/
│       │   ├── DocumentController.java      # Documents / Q&A / conversations / graph
│       │   ├── DocumentService.java         # Async ingestion / cascade delete / stats
│       │   └── DocumentUploadRequest / DocumentRequest / DocumentResponse / DocumentStatsResponse / DocumentSummary
│       ├── knowledge/
│       │   ├── KnowledgeEntity.java / KnowledgeEntityRepository.java   # Graph entities
│       │   ├── chunk/DocumentChunk.java / DocumentChunkRepository.java # Chunks (tenant-isolated Cypher)
│       │   ├── graph/GraphRagRequest.java / GraphRagResult.java / GraphRagService.java  # GraphRAG core
│       │   └── record/ChatMessage.java / ChatMessageRepository.java / ChatSessionSummary.java / ChatSessionSummaryRepository.java
│       ├── provider/AiProviderController.java   # GET /ai/v1/provider (AI platform probe)
│       └── request/ SessionRequest.java / FileUploadRequest.java / ApiKeyRequest.java
├── src/main/resources/
│   ├── application.yaml                    # Base config (Jackson/virtual threads/HTTP2/auth)
│   ├── application-baidu.yaml              # Baidu Qianfan profile (--spring.profiles.active=baidu)
│   ├── db/schema.sql                       # DDL (api_key etc., auto-run at startup)
│   └── static/                             # Frontend SPA
│       ├── index.html                      # Login / Q&A / Docs / Graph / API-Key mgmt + model dropdown
│       ├── css/style.css                   # Dark theme
│       ├── js/app.js                       # 401 interception / login / CSRF / key management
│       ├── js/marked.min.js                # Markdown renderer
│       ├── js/vis-network.min.js           # Graph visualization
│       └── favicon.ico
├── build.gradle                            # Gradle + Jib config
├── NEO4J.md                                # Neo4j setup guide
└── README.md                               # Chinese documentation
```

---

## Acknowledgements

This project uses the following excellent open-source projects. We extend our gratitude to their developers and contributors:

### Backend Framework & Runtime

| Project | Version | Purpose | License |
|---------|---------|---------|---------|
| [Spring Boot](https://spring.io/projects/spring-boot) | 4.1.1 | Application framework | Apache 2.0 |
| [Spring WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html) | 4.1.1 | Reactive web framework | Apache 2.0 |
| [Spring Security](https://spring.io/projects/spring-security) | 7.1 | Authentication & authorization (login + API Key + CSRF) | Apache 2.0 |
| [Spring AI](https://spring.io/projects/spring-ai) | 2.0.1 | AI abstraction (vector store / chat model) | Apache 2.0 |
| [Project Reactor](https://projectreactor.io/) | - | Reactive programming library | Apache 2.0 |
| [Lombok](https://projectlombok.org/) | 1.18.46 | Compile-time code generation | MIT |
| [Jackson 3.x](https://github.com/FasterXML/jackson) | 2.21.+ | JSON serialization/deserialization | Apache 2.0 |

### Database & Drivers

| Project | Version | Purpose | License |
|---------|---------|---------|---------|
| [Neo4j](https://neo4j.com/) | 5.x / 2026.x | Graph database + vector database | GPLv3 / Commercial |
| [Neo4j Java Driver](https://github.com/neo4j/neo4j-java-driver) | 6.1.0 | Official Neo4j driver | Apache 2.0 |
| [Spring Data Neo4j](https://spring.io/projects/spring-data-neo4j) | 8.1.1 | Neo4j ORM / Repository | Apache 2.0 |
| [MySQL](https://www.mysql.com/) + R2DBC | 8.x | Auth/business data (api_key, chat_message) | GPLv2 |

### Document Processing

| Project | Version | Purpose | License |
|---------|---------|---------|---------|
| [Apache PDFBox](https://pdfbox.apache.org/) | 2.0.29 | PDF text extraction | Apache 2.0 |
| [Apache POI](https://poi.apache.org/) | 5.4.0 | Word/Excel/PPT document parsing | Apache 2.0 |
| [Apache Commons IO](https://commons.apache.org/proper/commons-io/) | 2.18.0 | File I/O utilities | Apache 2.0 |
| [Apache Commons FileUpload](https://commons.apache.org/proper/commons-fileupload/) | 1.6.0 | File upload handling | Apache 2.0 |

### AI Services

| Project | Purpose |
|---------|---------|
| [Baidu Qianfan](https://qianfan.baidubce.com/) | deepseek-v3.2 chat models + bge-large-zh embeddings (OpenAI-compatible) |
| [Zhipu AI](https://open.bigmodel.cn/) | Compatible fallback (GLM + embedding, OpenAI-compatible) |

### Frontend

| Project | Purpose | License |
|---------|---------|---------|
| [Tailwind CSS](https://tailwindcss.com/) | Utility-first CSS framework | MIT |
| [marked.js](https://github.com/markedjs/marked) | Markdown renderer | MIT |
| [vis-network](https://github.com/visjs/vis-network) | Knowledge graph force-directed visualization | Apache 2.0 / MIT |

### Build & Deployment

| Project | Version | Purpose | License |
|---------|---------|---------|---------|
| [Gradle](https://gradle.org/) | 9.7.0 | Build tool | Apache 2.0 |
| [Jib](https://github.com/GoogleContainerTools/jib) | 3.5.4 | Container image build (no Dockerfile needed) | Apache 2.0 |

---

## License

This project is licensed under the **MIT License with Commercial Use Restriction**.

- **Personal non-commercial research/study**: Free and open — use, copy, modify, and distribute.
- **Commercial use**: Requires explicit written authorization from the copyright holder, including but not limited to integration into commercial products, paid services, or any use intended for commercial advantage or monetary compensation.

See [LICENSE](./LICENSE) for the full agreement.
