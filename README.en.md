# Motcs Commons — GraphRAG Multi-Hop Intelligent Search System

An enterprise-grade document knowledge base and multi-hop intelligent Q&A system built on **Spring Boot 4.1.1 + Neo4j + Spring AI 2.0**. Built with a pure WebFlux reactive architecture, supporting async document ingestion, enterprise-grade dual-layer chunking, vector retrieval + knowledge graph multi-hop reasoning, streaming conversations, and citation traceability.

---

## Features

### Intelligent Q&A
- Multi-turn conversations with automatic context via `sessionId`
- SSE streaming output with real-time Markdown rendering
- Dual-path retrieval: vector search + Neo4j multi-hop graph recall
- Each answer displays cited knowledge chunks, clickable to view source (with page navigation)
- Conversation history persisted in Neo4j, isolated by user/tenant/system
- Export conversations as Markdown, single/batch deletion
- Clear prompts when no results found (no docs / processing / mismatch)

### Document Management
- Supports 10 formats: PDF / Word / Excel / PPT / TXT / CSV / Markdown and more
- Immediate response on upload, graph construction runs asynchronously
- Real-time status polling: Processing → Completed / Failed
- Enterprise dual-layer chunking (fine-grained retrieval + coarse-grained reasoning) with page numbers and title hierarchy
- Cascade deletion by docCode: chunks, vectors, entities, relations, source files
- Deleted documents are marked in conversation citations to ensure data consistency
- Multi-tenant + multi-system isolation; users can only delete their own uploads
- Lightweight stats API (aggregated query, scales to millions of documents)
- Failed uploads support retry with auto-filled form

### Knowledge Graph
- Entity extraction and relation building with force-directed visualization
- Auto-stops physics simulation after stabilization, supports re-layout
- Filtered by tenant/system, node count limited to avoid performance issues

### Architecture
- Pure Spring WebFlux reactive, virtual threads + Reactor
- Neo4j serves as both vector database (VectorStore) and graph database
- Jib containerization, environment-variable-driven configuration, no rebuild needed
- Jackson 3.x global date formatting, JVM timezone fixed to Asia/Shanghai

---

## Tech Stack

| Category | Technology |
|----------|-----------|
| Framework | Spring Boot 4.1.1, Spring WebFlux |
| AI | Spring AI 2.0.0, OpenAI-compatible API (Zhipu GLM-4-Flash / embedding-3) |
| Database | Neo4j (vector + graph unified) |
| Document Processing | Apache PDFBox 2.0.29, Apache POI 5.4.0, commons-io 2.18.0 |
| Build | Gradle 9.7.0, Jib 3.5.4 |
| Language | Java 26 |
| Frontend | Vanilla HTML + Tailwind CSS + marked.js + vis-network |

---

## Quick Start

### Prerequisites

- JDK 26+
- Neo4j 5.x / 2026.x (with vector index enabled)
- Zhipu AI API Key (or any OpenAI-compatible endpoint)

### Local Development

1. **Start Neo4j**

   See [NEO4J.md](./NEO4J.md) for Neo4j installation and setup.

2. **Configure environment variables** (or edit `application-prod.yaml`)

   ```bash
   export NEO4J_URI=bolt://127.0.0.1:7687
   export NEO4J_AUTH_USERNAME=neo4j
   export NEO4J_AUTH_PASSWORD=your_password
   export AI_API_KEY=your_zhipu_api_key
   export AI_BASE_URL=https://open.bigmodel.cn/api/paas/v4
   ```

3. **Run the application**

   ```bash
   ./gradlew bootRun
   ```

4. **Open the UI**

   Visit `http://localhost:8080/` in your browser.

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

## Environment Variables

### Core

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | Server port |
| `NEO4J_URI` | `bolt://127.0.0.1:7687` | Neo4j connection URI |
| `NEO4J_AUTH_USERNAME` | `neo4j` | Neo4j username |
| `NEO4J_AUTH_PASSWORD` | - | Neo4j password |

### AI

| Variable | Default | Description |
|----------|---------|-------------|
| `AI_BASE_URL` | `https://open.bigmodel.cn/api/paas/v4` | AI API base URL (OpenAI-compatible) |
| `AI_API_KEY` | - | AI API key |
| `AI_CHAT_MODEL` | `glm-4-flash` | Chat model |
| `AI_CHAT_TEMPERATURE` | `0.1` | Chat temperature |
| `AI_EMBEDDING_MODEL` | `embedding-3` | Embedding model |
| `AI_EMBEDDING_DIMENSION` | `2048` | Embedding dimension |

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

## API Reference

### Document Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/documents/upload` | Upload document (multipart/form-data: file/title/description/docCode/tenantCode/systemType/userId) |
| `POST` | `/api/documents/upload-by-url` | Upload document via URL |
| `GET` | `/api/documents/list` | List documents (tenantCode + systemType) |
| `DELETE` | `/api/documents/docCode/{docCode}` | Delete document by docCode (cascade: chunks/vectors/entities/relations/files) |
| `GET` | `/api/documents/stats` | Document statistics (doc count + chunk count, lightweight aggregation) |
| `GET` | `/api/documents/health` | Health check |

### Intelligent Q&A

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/documents/query` | GraphRAG Q&A (SSE streaming, params: question/userId/sessionId/tenantCode/systemType) |

**SSE response order:**
```
__SESSION__:sess_xxx        # Session ID (auto-generated on first call)
__SOURCES__:[{...}]          # Cited knowledge chunks
data:answer tokens streamed...  # AI answer (Markdown)
```

### Conversation History

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/documents/sessions` | Session list (by userId + tenantCode + systemType, distinct sessionId) |
| `GET` | `/api/documents/conversations/session` | Full multi-turn conversation by sessionId |
| `GET` | `/api/documents/conversations` | Conversation records by user |
| `DELETE` | `/api/documents/conversations/session/{sessionId}` | Delete single session (all records) |
| `DELETE` | `/api/documents/conversations/batch` | Batch delete sessions (Body: sessionId array) |

### Knowledge Graph

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/documents/graph` | Get graph data (tenantCode + systemType + limit, default 200 nodes) |

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

### Neo4j
Spring Boot 4.x `spring-boot-starter-neo4j` only auto-configures the Driver. `Neo4jTemplate` / `MappingContext` must be declared manually (see `Neo4jConfig.java`). A unique constraint on `KnowledgeEntity.name` is created at startup to prevent race conditions in parallel entity extraction.

### Jackson 3.x
Spring Boot 4.1.1 uses Jackson 3.x (package `tools.jackson`). Custom `LocalDateTimeSerializer` is registered via `JsonMapperBuilderCustomizer`, global format `yyyy-MM-dd HH:mm:ss`.

### Timezone
`TimeZone.setDefault(Asia/Shanghai)` is set at the very beginning of `MotcsCommonsApplication.main()` to ensure correct time inside containers.

### Vector Store
Neo4jVectorStore uses label=`DocumentChunk`, index-name=`knowledge_vector_index`, cosine distance, 2048 dimensions. Fine-grained chunk IDs match vector node IDs — `saveAll` must NOT be used (it overwrites embeddings); use Cypher UPDATE for property changes.

---

## FAQ

**Q: Startup fails with `The client is unauthorized due to authentication failure`**
A: Incorrect Neo4j credentials. Check `NEO4J_AUTH_USERNAME` / `NEO4J_AUTH_PASSWORD` environment variables.

**Q: Uploading docx throws `NoSuchMethodError: BoundedInputStream.builder()`**
A: POI 5.4.0 requires commons-io 2.16+. The project uses 2.18.0.

**Q: AI answers return 429 rate limit**
A: Large document entity extraction already uses batching + concurrency limits (max 200 chunks, concurrency 2, 1.5s interval), and multi-hop recall is limited to 30. If still triggered, reduce `AI_CHAT_TEMPERATURE` or upgrade API quota.

**Q: Document stays "Processing" forever**
A: Graph construction is async; frontend polls every 30s. If stuck, check logs for entity extraction timeout or expired API key.

**Q: Docker container time is 8 hours behind Beijing**
A: JVM timezone is fixed to Asia/Shanghai in the startup class. Ensure you're using the latest image.

---

## Project Structure

```
motcs-commons/
├── src/main/java/com/motcs/
│   ├── MotcsCommonsApplication.java        # Entry point (timezone config)
│   ├── controller/
│   │   └── DocumentController.java         # WebFlux controller (13 endpoints)
│   ├── service/
│   │   └── DocumentService.java            # Document service (async ingest, cascade delete, aggregation)
│   ├── knowledge/
│   │   ├── graph/
│   │   │   ├── GraphRagKnowledgeService.java   # GraphRAG core (retrieval + multi-hop + streaming + history)
│   │   │   ├── GraphRagQuery.java              # Q&A request DTO
│   │   │   └── GraphRagMultiHopResult.java     # Multi-hop result
│   │   ├── chunk/
│   │   │   ├── DocumentChunk.java              # Chunk entity (page/hierarchy/relation IDs)
│   │   │   └── DocumentChunkRepository.java    # Chunk repo (aggregation/Cypher multi-hop)
│   │   ├── record/
│   │   │   ├── ConversationRecord.java         # Conversation record entity
│   │   │   └── ConversationRecordRepository.java
│   │   ├── KnowledgeEntity.java                # Knowledge graph entity
│   │   └── KnowledgeEntityRepository.java
│   ├── dto/                                # Request/response DTOs
│   ├── util/
│   │   ├── AnyDocConverterUtil.java        # Multi-format doc to Markdown
│   │   ├── EnterpriseChunker.java          # Enterprise dual-layer chunker
│   │   └── ByteArrayMultipartFile.java     # WebFlux file adapter
│   ├── commons/
│   │   ├── Utils.java                      # Utilities (format check/buffer merge)
│   │   └── FileUtils.java                  # File utils (page extract/range parse)
│   └── config/
│       ├── Neo4jConfig.java                # Neo4j manual config (Template/Mapping/constraint)
│       ├── AiConfig.java                   # AI config (separate ChatClient/EmbeddingModel)
│       └── WebConfiguration.java           # Jackson 3.x date formatting
├── src/main/resources/
│   ├── application.yaml                    # Base config (Jackson/virtual threads/HTTP2)
│   ├── application-prod.yaml               # Prod config (env var placeholders)
│   └── static/                             # Frontend SPA
│       ├── index.html                      # Main page (3 tabs: Q&A/Docs/Graph)
│       ├── css/style.css                   # Dark theme styles
│       ├── js/app.js                       # Frontend logic
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
| [Spring AI](https://spring.io/projects/spring-ai) | 2.0.0 | AI abstraction (vector store / chat model / chat memory) | Apache 2.0 |
| [Project Reactor](https://projectreactor.io/) | - | Reactive programming library | Apache 2.0 |
| [Lombok](https://projectlombok.org/) | 1.18.46 | Compile-time code generation | MIT |
| [Jackson 3.x](https://github.com/FasterXML/jackson) | 2.21.+ | JSON serialization/deserialization | Apache 2.0 |

### Database & Drivers

| Project | Version | Purpose | License |
|---------|---------|---------|---------|
| [Neo4j](https://neo4j.com/) | 5.x / 2026.x | Graph database + vector database | GPLv3 / Commercial |
| [Neo4j Java Driver](https://github.com/neo4j/neo4j-java-driver) | 6.1.0 | Official Neo4j driver | Apache 2.0 |
| [Spring Data Neo4j](https://spring.io/projects/spring-data-neo4j) | 8.1.1 | Neo4j ORM / Repository | Apache 2.0 |

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
| [Zhipu AI](https://open.bigmodel.cn/) | GLM-4-Flash chat model + embedding-3 vector model (OpenAI-compatible API) |

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
