package com.motcs.core.knowledge.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.motcs.commons.ContextUtil;
import com.motcs.commons.annotation.RestServerException;
import com.motcs.core.document.DocumentResponse;
import com.motcs.core.document.DocumentService;
import com.motcs.core.document.DocumentUploadRequest;
import com.motcs.core.knowledge.KnowledgeEntity;
import com.motcs.core.knowledge.KnowledgeEntityRepository;
import com.motcs.core.knowledge.chunk.DocumentChunk;
import com.motcs.core.knowledge.chunk.DocumentChunkRepository;
import com.motcs.core.knowledge.record.ChatMessage;
import com.motcs.core.knowledge.record.ChatMessageRepository;
import com.motcs.core.knowledge.record.ChatSessionSummary;
import com.motcs.core.knowledge.record.ChatSessionSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * GraphRAG知识库服务
 * 结合向量检索和图谱检索实现高质量知识问答
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphRagService {

    /**
     * 摘要时保留最近N轮原始对话
     */
    private static final int HISTORY_KEEP_RECENT = 10;
    /**
     * AI 实体+关系抽取 Prompt（用 __TEXT__ 占位，避免 StringTemplate 与 JSON 大括号冲突）
     */
    private static final String ENTITY_EXTRACTION_PROMPT = """
            请从下面的文本中抽取知识实体和实体之间的关系，只返回JSON，不要任何解释或Markdown代码块。
            实体只抽取最重要的，最多8个，类型：地点、景点、路线、人物、组织、关键事件、特色美食等重要的类型。
            不要抽取：日期、数字、普通词汇、形容词、副词。
            关系只抽取文本中明确存在的关联，如"包含"、"途经"、"位于"、"相邻"、"属于"等。
            输出格式：{"entities":[{"name":"实体名","type":"实体类型"}],"relations":[{"source":"实体A","target":"实体B","type":"关系类型"}]}
            文本：
            __TEXT__
            """;
    /**
     * 系统提示词（含多轮历史和知识库上下文）
     */
    private static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手，请严格依据下面提供的知识库上下文回答用户问题。
            如果上下文没有答案，直接回复知识库无相关内容，不要编造信息。
            
            【回答要求】
            - 直接、自然地回答问题，只输出答案本身，不要复述或引用原文片段。
            - 回答中不要出现"原文""系统""知识库上下文""根据资料""根据上下文"等元描述字样。
            - 不要输出或罗列文档元数据（如系统、角色、完成时间、平台等字段），不要用"系统：""角色："这类前缀搬运信息。
            - 引用来源已由界面单独展示，回答正文中无需再标注、引用或说明出处。
            - 依据上下文用自己的话组织答案，保证信息准确完整，直接给出用户需要的结论。
            
            【格式要求】回答使用 标准的Markdown 格式：
            - 用 ### 标题分段
            - 用 **加粗** 突出关键点
            - 用 - 或 1. 列表列举
            - 代码用 ``` 包裹
            
            【重要约定】
            - 回答正文中禁止出现孤立或多余的星号、井号等未闭合的 Markdown 标记（如单独成行的 *、**、***、#### 等），所有标记必须成对闭合，保证渲染后整洁。
            - 思考过程请使用简洁的纯文本叙述，不要使用 **、*、#、`、- 等任何 Markdown 标记符号，也不要添加序号或列表符号。
            
            【历史对话】
            {history}
            
            【知识库上下文】
            {context}
            """;
    private final ChatClient chatClient;
    private final Neo4jClient neo4jClient;
    private final VectorStore vectorStore;
    private final DocumentService documentService;
    private final DocumentChunkRepository chunkRepository;
    private final KnowledgeEntityRepository entityRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatSessionSummaryRepository summaryRepository;

    /**
     * GraphRAG 问答（含多轮上下文 + 来源返回）
     * 步骤1：向量相似度召回top5文档（按租户/系统/启用状态过滤）
     * 步骤2：基于初次结果做Neo4j多跳图查询，拿到关联实体、关联文档
     * 步骤3：合并向量上下文+图谱上下文，组装prompt交给AI分析
     *
     * @param ragQuery 用户提问参数（含 userId、sessionId）
     * @return QueryResult（来源列表 + 回答 token 流）
     */
    public Mono<QueryResult> graphRagQueryStream(GraphRagRequest ragQuery) {
        log.info("收到GraphRAG查询: question={}, userId={}, sessionId={}, tenant={}, system={}",
                ragQuery.getQuestion(), ragQuery.getUserId(), ragQuery.getSessionId(),
                ragQuery.getTenantCode(), ragQuery.getSystemType());

        return Mono.fromCallable(() -> {
            // 1. 拉取多轮对话历史（长对话自动摘要压缩，永远只取最新5条原始+摘要）
            String historyContext = buildHistoryContext(ragQuery.getSessionId());
            // 2. 向量检索 + 图谱召回，同时提取来源
            ContextResult ctx = buildContextWithSources(ragQuery);
            if (ctx == null) return null;
            return new ContextResult(ctx.fullContext(), ctx.sources(), historyContext);
        }).subscribeOn(Schedulers.boundedElastic()).map(ctx -> {
            ChatClient.ChatClientRequestSpec answerSpec = this.chatClient.prompt()
                    .system(s -> s.text(SYSTEM_PROMPT)
                            .param("context", ctx.fullContext())
                            .param("history", ctx.historyContext()))
                    .user(ragQuery.getQuestion());
            applyModelOptions(answerSpec, ragQuery.getModel());
            Flux<ChatStreamEvent> answer = streamChatEvents(answerSpec);
            return new QueryResult(ctx.sources(), answer);
        }).switchIfEmpty(Mono.fromCallable(() -> {
            // 向量检索无结果：仍调用 AI，让其判断是打招呼/闲聊还是知识问题
            // 打招呼类客气回复，知识类说明未找到相关内容
            String noResultPrompt = """
                    你是一个友好的智能助手。当前知识库中没有检索到与用户问题相关的文档内容。
                    
                    请根据用户问题判断：
                    1. 如果是打招呼、问候、闲聊（如"你好"、"谢谢"、"你是谁"等），请礼貌、自然地回复，不要提及知识库。
                    2. 如果是询问具体知识、文档内容、业务问题，请礼貌告知当前知识库中暂无相关内容，建议用户上传相关文档或换个问题。
                    3. 你主要能力：分析文档，通过私有知识库回答问题。
                    4. 回答的内容中不要带有文档原文这样的字眼，只需要根据文档内容回答即可，界面已经显示了引用的文档内容。
                    
                    回复使用 标准的Markdown 格式，简洁友好，不超过100字。""";
            ChatClient.ChatClientRequestSpec noResultSpec = this.chatClient.prompt()
                    .system(noResultPrompt)
                    .user(ragQuery.getQuestion());
            applyModelOptions(noResultSpec, ragQuery.getModel());
            Flux<ChatStreamEvent> answer = streamChatEvents(noResultSpec);
            return new QueryResult(List.of(), answer);
        })).doOnError(e -> log.error("GraphRAG查询出错: {}", e.getMessage(), e));
    }

    /**
     * 按用户前端选择的模型动态覆盖 ChatClient 请求（per-request options），
     * 不传或空白时使用配置文件里的默认模型。
     */
    private void applyModelOptions(ChatClient.ChatClientRequestSpec spec, String model) {
        if (model != null && !model.isBlank()) {
            spec.options(OpenAiChatOptions.builder().model(model));
            log.info("本次问答使用用户选择模型: {}", model);
        }
    }

    /**
     * 将 Spring AI 流式响应转换为 SSE 事件流。
     * 每个 chunk 可能携带思考片段（metadata["reasoningContent"]）或回答片段（getText()），
     * 思考片段输出 type=reasoning，正文片段输出 type=content（仅当正文实际有文本时下发，
     * 思考阶段 getText() 为空，不发送空 content 事件，避免前端误判为"正文开始"而折叠思考框）。
     */
    private Flux<ChatStreamEvent> streamChatEvents(ChatClient.ChatClientRequestSpec promptSpec) {
        // Spring AI 2.0.1 每个流式 chunk 的 metadata["reasoningContent"] 是累计值，
        // 这里只取新增片段下发，避免前端重复拼接。
        AtomicReference<String> lastReasoning = new AtomicReference<>("");
        return promptSpec.stream().chatResponse().concatMap(response -> {
            if (response.getResult() == null) {
                return Flux.empty();
            }
            AssistantMessage output = response.getResult().getOutput();
            List<ChatStreamEvent> events = new ArrayList<>(2);
            Object reasoning = output.getMetadata().get("reasoningContent");
            if (reasoning instanceof String r && StringUtils.hasText(r)) {
                String prev = lastReasoning.getAndSet(r);
                if (r.length() > prev.length()) {
                    events.add(new ChatStreamEvent("reasoning", r.substring(prev.length())));
                }
            }
            // 正文片段：仅当实际有文本时下发（思考阶段 getText() 为空，不发送空 content 事件）
            String text = output.getText();
            if (text != null && !text.isEmpty()) {
                events.add(new ChatStreamEvent("content", text));
            }
            return Flux.fromIterable(events);
        });
    }

    /**
     * 便捷方法：拼接收到的回答文本（只取正文，过滤思考内容），供测试等同步场景调用。
     */
    public Mono<String> graphRagQuery(GraphRagRequest ragQuery) {
        return this.graphRagQueryStream(ragQuery).flatMap(result -> {
            // 思考内容仅供前端实时展示，阻塞式拼接时只取正文片段
            return result.answer().filter(ev -> "content".equals(ev.type()))
                    .map(ChatStreamEvent::text)
                    .collectList().map(list -> String.join("", list));
        });
    }

    /**
     * 构建 GraphRAG 查询上下文（含来源提取）
     *
     * @return ContextResult；向量检索无结果时返回 null
     */
    private ContextResult buildContextWithSources(GraphRagRequest request) {
        // 1、向量检索（按租户/系统/启用状态过滤）
        // 租户0 = 超管上传的全局默认文档，对所有租户开放：检索时同时命中指定租户与租户0；
        // 当查询租户本身为 0 时不过滤租户，检索所有租户的全部文档（超管全局视角）
        if (!StringUtils.hasLength(request.getTenantCode())) {
            throw RestServerException.withMsg("租户编码必填");
        } else if (!StringUtils.hasLength(request.getSystemType())) {
            throw RestServerException.withMsg("系统类型必填");
        }

        // 租户0（超管全局文档）不限系统类型；指定租户的文档按系统类型过滤
        String filterExpr = request.getTenantCode().equals("0") ? "enabled == true && status == 'SUCCESS'" :
                String.format("((tenantCode == '%s' && systemType == '%s') || tenantCode == '0') && enabled == true && status == 'SUCCESS'",
                        request.getTenantCode(), request.getSystemType());
        SearchRequest searchRequest = SearchRequest.builder()
                .query(request.getQuestion()).topK(request.getTopK())
                .similarityThreshold(request.getThreshold())
                .filterExpression(filterExpr).build();
        List<Document> vectorDocs = this.vectorStore.similaritySearch(searchRequest);

        if (vectorDocs.isEmpty()) {
            log.info("向量检索未找到相关文档");
            return null;
        }

        // 租户自定义优先：指定租户（非0）的文档排在前，超管默认文档（租户0）排在后，
        // 二者内容有出入时让 AI 优先参考租户自定义内容；租户0 查询全部时保持相似度排序
        if (!request.getTenantCode().equals("0")) {
            vectorDocs = vectorDocs.stream().sorted(Comparator.comparingInt(d -> {
                Object t = d.getMetadata().get("tenantCode");
                return t != null && request.getTenantCode().equals(String.valueOf(t)) ? 0 : 1;
            })).toList();
        }
        log.info("向量检索找到 {} 个相关文档（租户{}优先）", vectorDocs.size(), request.getTenantCode());

        // 2、提取知识库来源（供前端展示和点击查看原文）
        List<Map<String, Object>> sources = vectorDocs.stream().map(doc -> {
            Map<String, Object> src = new LinkedHashMap<>();
            Map<String, Object> meta = doc.getMetadata();
            src.put("fileName", meta.getOrDefault("source", "未知文件"));
            src.put("title", meta.getOrDefault("title", "未命名文档"));
            src.put("docCode", meta.get("docCode"));
            src.put("chunkIndex", meta.get("chunkIndex"));
            src.put("pageNumber", meta.getOrDefault("pageNumber", 1));
            src.put("titleHierarchy", meta.get("titleHierarchy"));
            src.put("content", doc.getText());
            src.put("deleted", false);
            return src;
        }).toList();

        // 2.5、联动父级粗粒度切片，补全推理上下文（多跳 RAG 核心）
        Set<String> parentIds = vectorDocs.stream()
                .map(doc -> doc.getMetadata().get("parentChunkId"))
                .filter(Objects::nonNull).map(Object::toString)
                .filter(id -> !id.isBlank()).collect(Collectors.toSet());
        String coarseContext = "";
        if (!parentIds.isEmpty()) {
            List<DocumentChunk> chunkList = chunkRepository.findAllById(parentIds);
            List<DocumentChunk> parentChunks = new ArrayList<>(chunkList);
            if (!parentChunks.isEmpty()) {
                coarseContext = parentChunks.stream().map(DocumentChunk::getContent)
                        .filter(Objects::nonNull).distinct().collect(Collectors.joining("\n---\n"));
                log.info("联动 {} 个父级粗粒度切片补全上下文", parentChunks.size());
            }
        }

        // 3、多跳图谱召回（限制数量，避免 prompt 过大触发限流/超时）
        List<String> chunkIds = vectorDocs.stream().map(Document::getId).toList();
        List<GraphRagResult> multiHopResults =
                this.chunkRepository.multiHopRetrieve(chunkIds, request.getTenantCode(), request.getSystemType(), 30);
        log.info("多跳图谱召回找到 {} 个关联文档块", multiHopResults.size());

        // 4、拼接上下文（细粒度检索 + 粗粒度推理补全 + 图谱多跳），限制总长度避免 token 超限
        String vectorContext = vectorDocs.stream().map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
        String graphContext = multiHopResults.stream()
                .flatMap(result -> Stream.of(result.getSourceContent(), result.getOtherContent()))
                .filter(Objects::nonNull).distinct().collect(Collectors.joining("\n***\n"));

        // 限制图谱上下文最大 6000 字符，防止 prompt 爆炸
        final int MAX_GRAPH_CTX = 6000;
        if (graphContext.length() > MAX_GRAPH_CTX) {
            graphContext = graphContext.substring(0, MAX_GRAPH_CTX) + "\n...（图谱关联内容已截断）";
        }

        StringBuilder fullContext = new StringBuilder("【向量检索片段】\n").append(vectorContext);
        if (!coarseContext.isBlank()) {
            fullContext.append("\n【推理上下文补全（父级粗粒度切片）】\n").append(coarseContext);
        }
        fullContext.append("\n【图谱多跳关联信息】\n").append(graphContext);
        return new ContextResult(fullContext.toString(), sources, null);
    }

    /**
     * 获取知识图谱数据（用于前端可视化）
     * 返回 DocumentChunk 节点、KnowledgeEntity 节点及 MENTIONS / RELATE_TO 关系
     *
     * @param tenantCode 租户编码（可选，为空则不过滤）
     * @param systemType 系统类型（可选，为空则不过滤）
     * @param limit      最大返回的关系条数
     * @return {nodes: [...], edges: [...]}
     */
    public Mono<Map<String, Object>> getGraphData(String tenantCode, String systemType, int limit) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> edges = new ArrayList<>();
            Set<String> nodeIds = new HashSet<>();

            // 1. 查询 DocumentChunk -[MENTIONS]-> KnowledgeEntity
            String mentionsCypher = """
                    MATCH (c:DocumentChunk)-[m:MENTIONS]->(e:KnowledgeEntity)
                    WHERE ($tc IS NULL OR $tc = '0' OR c.tenantCode = $tc OR c.tenantCode = '0')
                    AND ($st IS NULL OR c.systemType = $st)
                    RETURN c.id AS chunkId, c.fileName AS fileName, c.content AS content,
                           c.chunkIndex AS chunkIndex,
                           e.id AS entityId, e.name AS entityName, e.type AS entityType
                    LIMIT $limit
                    """;
            Collection<Map<String, Object>> mentionRows = this.neo4jClient.query(mentionsCypher)
                    .bind(tenantCode).to("tc").bind(systemType).to("st")
                    .bind(limit).to("limit").fetch().all();

            for (Map<String, Object> row : mentionRows) {
                String chunkId = "chunk_" + row.get("chunkId");
                String entityId = "entity_" + row.get("entityId");

                if (!nodeIds.contains(chunkId)) {
                    nodeIds.add(chunkId);
                    String content = (String) row.get("content");
                    String preview = content != null && content.length() > 60
                            ? content.substring(0, 60) + "..." : content;
                    nodes.add(Map.of("id", chunkId, "label",
                            (row.get("fileName") != null ? row.get("fileName") : "分片")
                                    + "#" + (row.get("chunkIndex") != null ? row.get("chunkIndex") : ""),
                            "group", "chunk", "title", preview != null ? preview : "", "shape", "box"));
                }
                if (!nodeIds.contains(entityId)) {
                    nodeIds.add(entityId);
                    nodes.add(Map.of("id", entityId,
                            "label", row.get("entityName") != null ? row.get("entityName") : "未知",
                            "group", "entity",
                            "type", row.get("entityType") != null ? row.get("entityType") : "未知",
                            "shape", "dot"));
                }
                edges.add(Map.of("from", chunkId, "to", entityId, "label", "提及", "color", "#94a3b8"));
            }

            // 2. 查询 KnowledgeEntity -[RELATE_TO]-> KnowledgeEntity
            //    租户规则与 MENTIONS 一致：租户 0/不传查所有租户的关系；
            //    非 0 租户只查"任一端实体被该租户或全局租户(0)文档提及"的关系，
            //    保证图谱视图与文档/对话的租户隔离规则一致。
            String relateCypher = """
                    MATCH (e1:KnowledgeEntity)-[r:RELATE_TO]->(e2:KnowledgeEntity)
                    WHERE ($tc IS NULL OR $tc = '0'
                           OR EXISTS((e1)<-[:MENTIONS]-(:DocumentChunk {tenantCode: $tc}))
                           OR EXISTS((e1)<-[:MENTIONS]-(:DocumentChunk {tenantCode: '0'}))
                           OR EXISTS((e2)<-[:MENTIONS]-(:DocumentChunk {tenantCode: $tc}))
                           OR EXISTS((e2)<-[:MENTIONS]-(:DocumentChunk {tenantCode: '0'})))
                    AND ($st IS NULL
                           OR EXISTS((e1)<-[:MENTIONS]-(:DocumentChunk {systemType: $st}))
                           OR EXISTS((e2)<-[:MENTIONS]-(:DocumentChunk {systemType: $st})))
                    RETURN e1.id AS sourceId, e1.name AS sourceName,
                     e2.id AS targetId, e2.name AS targetName, r.type AS relType LIMIT $limit
                    """;
            Collection<Map<String, Object>> relateRows = this.neo4jClient.query(relateCypher)
                    .bind(tenantCode).to("tc").bind(systemType).to("st")
                    .bind(limit).to("limit").fetch().all();

            for (Map<String, Object> row : relateRows) {
                String sourceId = "entity_" + row.get("sourceId");
                String targetId = "entity_" + row.get("targetId");

                // 确保两端节点都在列表中（可能来自 MENTIONS 查询未覆盖的实体）
                if (!nodeIds.contains(sourceId)) {
                    nodeIds.add(sourceId);
                    nodes.add(Map.of("id", sourceId,
                            "label", row.get("sourceName") != null ? row.get("sourceName") : "未知",
                            "group", "entity", "type", "未知", "shape", "dot"));
                }
                if (!nodeIds.contains(targetId)) {
                    nodeIds.add(targetId);
                    nodes.add(Map.of("id", targetId,
                            "label", row.get("targetName") != null ? row.get("targetName") : "未知",
                            "group", "entity", "type", "未知", "shape", "dot"));
                }
                edges.add(Map.of("from", sourceId, "to", targetId,
                        "label", row.get("relType") != null ? row.get("relType") : "关联",
                        "color", "#6366f1"));
            }

            log.info("图谱数据查询完成: 节点={}, 边={}", nodes.size(), edges.size());
            return Map.<String, Object>of("nodes", nodes, "edges", edges);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 文档入库（异步处理）：
     * 同步：校验 → 保存文件 → 创建占位分片(PROCESSING) → 立即返回。
     * 异步：文档转换 → 切分 → 向量入库 → 保存分片(SUCCESS) → 实体抽取 → 图谱关系构建。
     * 失败：删除已入库向量，占位分片标记 FAILED。
     */
    public Mono<DocumentResponse> insertKnowledgeDoc(DocumentUploadRequest request) {
        log.info("开始处理文档入库: fileName={}, docCode={}", request.getFileName(), request.getDocCode());

        return this.documentService.uploadDocument(request).flatMap(context -> {
            DocumentResponse response = context.getResponse();
            if ("FAILED".equals(response.getStatus())) {
                log.error("文档入库初始化失败: {}", response.getErrorMessage());
                return Mono.just(response);
            }

            // 触发完整异步处理链：文档处理 → 图谱构建 → 标记SUCCESS；失败则清理并标记FAILED
            Mono<Object> fromRunnable = Mono.fromRunnable(() -> {
                if (request.getDocCode() != null && !request.getDocCode().isBlank()) {
                    buildKnowledgeGraph(request.getDocCode());
                    log.info("图谱构建完成: docCode={}", request.getDocCode());
                }
            });
            Mono<Object> other = Mono.fromRunnable(() -> this.documentService
                    .markDocumentSuccess(context.getDocumentId()));
            this.documentService.processDocumentAsync(request, context)
                    .then(fromRunnable).then(other).onErrorResume(e -> {
                        log.error("异步文档处理失败: {}", e.getMessage(), e);
                        this.documentService.failUpload(context, e.getMessage());
                        return Mono.empty();
                    }).subscribeOn(Schedulers.boundedElastic()).subscribe();
            log.info("文档已提交异步处理: documentId={}, fileName={}, status=PROCESSING",
                    response.getDocumentId(), response.getFileName());
            return Mono.just(response);
        });
    }

    /**
     * 对指定 docCode 的所有分片做实体抽取，并建立 MENTIONS 关系
     * 优化：批量合并抽取（3个分片合并一次AI调用），降低并发，批次间延时，避免 429
     */
    private void buildKnowledgeGraph(String docCode) {
        List<DocumentChunk> chunks = this.chunkRepository.findByDocCode(docCode);
        if (chunks.isEmpty()) {
            log.warn("未找到 docCode={} 的分片，跳过图谱构建", docCode);
            return;
        }

        // 仅对细粒度分片做实体抽取（粗粒度用于推理补全，不需要实体抽取）
        List<DocumentChunk> fineChunks = chunks.stream()
                .filter(c -> c.getChunkIndex() != null && c.getChunkIndex() >= 0)
                .toList();
        if (fineChunks.isEmpty()) {
            log.warn("docCode={} 无细粒度分片，跳过图谱构建", docCode);
            return;
        }

        // 超大文档限制：最多 200 个分片参与实体抽取，超过的只入向量库不建图谱
        // （几千片的文档全量抽取既慢又容易 429，前 200 片已覆盖文档核心实体）
        final int MAX_CHUNKS_FOR_GRAPH = 200;
        if (fineChunks.size() > MAX_CHUNKS_FOR_GRAPH) {
            log.info("docCode={} 分片数 {} 超过上限 {}, 仅对前 {} 片做实体抽取",
                    docCode, fineChunks.size(), MAX_CHUNKS_FOR_GRAPH, MAX_CHUNKS_FOR_GRAPH);
            fineChunks = fineChunks.subList(0, MAX_CHUNKS_FOR_GRAPH);
        }

        // 动态批次大小：分片越多，批次越大，减少 AI 调用次数
        // <=50片: 3片/批, 51-150片: 5片/批, >150片: 8片/批
        int batchSize = fineChunks.size() <= 50 ? 3 : (fineChunks.size() <= 150 ? 5 : 8);
        List<List<DocumentChunk>> batches = new ArrayList<>();
        for (int i = 0; i < fineChunks.size(); i += batchSize) {
            batches.add(fineChunks.subList(i, Math.min(i + batchSize, fineChunks.size())));
        }

        log.info("开始为 docCode={} 构建图谱：细粒度分片 {} 个，分 {} 批（每批 {} 片，并发 2）",
                docCode, fineChunks.size(), batches.size(), batchSize);

        // 并发 2 路，批次间 1.5 秒延时，平滑请求速率
        int concurrency = Math.min(batches.size(), 2);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int bi = 0; bi < batches.size(); bi++) {
                final int batchIdx = bi;
                final List<DocumentChunk> batch = batches.get(bi);
                futures.add(CompletableFuture.runAsync(() -> {
                    // 批次间延时：第 N 批等待 N * 1.5s，避免瞬时并发冲击
                    try {
                        Thread.sleep((long) batchIdx * 1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    processBatchEntities(batch);
                }, executor).exceptionally(ex -> {
                    log.warn("批次 {} 实体抽取异常: {}", batchIdx, ex.getMessage());
                    return null;
                }));
            }

            // 总超时按批次数动态计算（每批最多 300s，与 spring.ai.openai.timeout 对齐）。
            // 千帆思考模型（deepseek-v3.2-think）单次实体抽取响应慢，90s/批过紧会误判超时
            long timeoutSec = Math.max(300, (long) batches.size() * 300L / concurrency);
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(timeoutSec, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            log.warn("图谱构建部分失败: {}", e.getMessage());
        } finally {
            executor.shutdown();
        }

        log.info("图谱构建完成: docCode={}, 细粒度分片={}, 批次数={}", docCode, fineChunks.size(), batches.size());
    }

    /**
     * 批量处理：将多个分片 内容合并后一次 AI 调用抽取实体，结果应用到批次内所有分片
     */
    private void processBatchEntities(List<DocumentChunk> batch) {
        // 合并批次内所有分片 内容，用分隔符区分
        String combined = batch.stream()
                .map(DocumentChunk::getContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n---\n"));
        if (combined.isBlank()) return;

        EntityExtractionResult extraction = extractEntities(combined);
        if (extraction == null || extraction.entities() == null || extraction.entities().isEmpty()) {
            return;
        }

        // 抽取结果应用到批次内每个分片（同文档内实体共享是合理的）
        for (DocumentChunk chunk : batch) {
            applyExtractionToChunk(chunk, extraction);
        }
    }

    /**
     * 将抽取结果应用到单个分片（保存实体 + 建立 MENTIONS / RELATE_TO 关系）
     */
    private void applyExtractionToChunk(DocumentChunk chunk, EntityExtractionResult extraction) {
        // 1. 实体去重保存（同名实体复用，唯一约束兜底）
        List<KnowledgeEntity> savedEntities = new ArrayList<>();
        for (ExtractedEntity e : extraction.entities()) {
            if (e.name() == null || e.name().isBlank()) continue;
            KnowledgeEntity entity = KnowledgeEntity.builder().name(e.name().trim())
                    .type(e.type() != null ? e.type().trim() : "未知").build();
            KnowledgeEntity saved = this.entityRepository.findByName(entity.getName()).orElseGet(() -> {
                try {
                    return this.entityRepository.save(entity);
                } catch (Exception ex) {
                    return this.entityRepository.findByName(entity.getName()).orElse(null);
                }
            });
            if (saved != null) {
                savedEntities.add(saved);
            }
        }

        if (savedEntities.isEmpty()) {
            return;
        }

        // 2. 建立 DocumentChunk -[MENTIONS]-> KnowledgeEntity 关系（用 Cypher，避免 save 覆盖向量库属性）
        for (KnowledgeEntity entity : savedEntities) {
            try {
                String cypher = """
                        MATCH (c:DocumentChunk{id:$chunkId}), (e:KnowledgeEntity{name:$entityName})
                        MERGE (c)-[:MENTIONS]->(e)
                        """;
                this.neo4jClient.query(cypher)
                        .bind(chunk.getId()).to("chunkId")
                        .bind(entity.getName()).to("entityName")
                        .fetch().one();
            } catch (Exception ex) {
                log.warn("建立 MENTIONS 关系失败: chunkId={}, entity={}, {}",
                        chunk.getId(), entity.getName(), ex.getMessage());
            }
        }

        // 3. 建立实体之间的 RELATE_TO 关系（用 MERGE 避免并行覆盖）
        if (extraction.relations() != null) {
            for (ExtractedRelation rel : extraction.relations()) {
                if (rel.source() == null || rel.target() == null) continue;
                createRelateToRelation(rel.source().trim(), rel.target().trim(),
                        rel.type() != null ? rel.type().trim() : "关联");
            }
        }
    }

    /**
     * 用 MERGE 建立实体间 RELATE_TO 关系，避免并行覆盖
     */
    private void createRelateToRelation(String sourceName, String targetName, String relationType) {
        try {
            String cypher = """
                    MATCH (a:KnowledgeEntity{name:$source}), (b:KnowledgeEntity{name:$target})
                     MERGE (a)-[r:RELATE_TO]->(b) SET r.type = $type
                    """;
            this.neo4jClient.query(cypher).bind(sourceName)
                    .to("source").bind(targetName).to("target")
                    .bind(relationType).to("type").fetch().one();
        } catch (Exception e) {
            log.warn("建立 RELATE_TO 关系失败: {} -[{}]-> {}, {}", sourceName, relationType, targetName, e.getMessage());
        }
    }

    /**
     * 调用 AI 从文本中抽取实体和关系，返回原始抽取结果。
     * 单分片超时 60 秒，失败后重试 1 次（间隔 2 秒）。
     */
    private EntityExtractionResult extractEntities(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String prompt = ENTITY_EXTRACTION_PROMPT.replace("__TEXT__", text);
        int maxAttempts = 2;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return CompletableFuture.supplyAsync(() -> this.chatClient.prompt().user(prompt)
                                .call().entity(EntityExtractionResult.class))
                        .orTimeout(600, TimeUnit.SECONDS).get();
            } catch (Exception e) {
                log.warn("实体抽取第{}/{}次失败: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 构建多轮对话上下文：长对话自动摘要压缩，永远只取最新5条原始对话+摘要
     * 用户看到的聊天记录不变，仅减少发给AI的token
     */
    private String buildHistoryContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return "（无历史对话）";

        // 1. 准确统计会话总条数
        Long count = this.chatMessageRepository.countBySessionId(sessionId).block();
        int totalCount = count != null ? count.intValue() : 0;
        if (totalCount == 0) return "（无历史对话）";

        // 2. 取最近5条原始对话（倒序查询后反转为正序）
        List<ChatMessage> recent = this.chatMessageRepository
                .findRecentBySessionId(sessionId, HISTORY_KEEP_RECENT, 0).collectList().block();
        if (recent == null || recent.isEmpty()) return "（无历史对话）";
        Collections.reverse(recent);

        String recentText = recent.stream()
                .map(h -> "用户：" + h.getQuestion() + "\n助手：" + (h.getAnswer() != null ? h.getAnswer() : ""))
                .collect(Collectors.joining("\n\n"));

        // 不足5条，直接返回原始对话
        if (totalCount <= HISTORY_KEEP_RECENT) {
            log.info("加载多轮历史 {} 条（未触发摘要）", totalCount);
            return recentText;
        }

        // 3. 超过5条，查询已有摘要
        ChatSessionSummary summaryRecord = null;
        try {
            summaryRecord = this.summaryRepository.findBySessionId(sessionId).block();
        } catch (Exception e) {
            log.warn("查询会话摘要失败: {}", e.getMessage());
        }

        int lastCount = (summaryRecord != null && summaryRecord.getLastMessageCount() != null)
                ? summaryRecord.getLastMessageCount() : 0;
        // 每新增5条触发一次重新摘要（第5、10、15...条时）
        boolean needRecordSummary = summaryRecord == null
                || summaryRecord.getSummary() == null || summaryRecord.getSummary().isBlank()
                || (totalCount - lastCount) >= HISTORY_KEEP_RECENT;

        String summary;
        if (needRecordSummary) {
            // 本次不阻塞，异步生成摘要，下次提问自动使用
            int olderCount = totalCount - HISTORY_KEEP_RECENT;
            String oldSummary = (summaryRecord != null && summaryRecord.getSummary() != null)
                    ? summaryRecord.getSummary() : "";
            Mono.fromRunnable(() -> {
                // 加载老对话（正序，取最早的 olderCount 条，即除最近5条外的全部）
                List<ChatMessage> older = this.chatMessageRepository
                        .findBySessionId(sessionId, olderCount).collectList().block();
                if (older == null || older.isEmpty()) return;
                String olderText = older.stream()
                        .map(h -> "用户：" + h.getQuestion() + "\n助手：" + (h.getAnswer() != null ? h.getAnswer() : ""))
                        .collect(Collectors.joining("\n\n"));
                if (!oldSummary.isBlank()) {
                    olderText = "【此前摘要】\n" + oldSummary + "\n\n【新增对话】\n" + olderText;
                }
                String newSummary = summarizeHistory(olderText);
                saveSummary(sessionId, newSummary, totalCount);
                log.info("异步生成会话摘要完成: sessionId={}, 老对话{}条, 总{}条", sessionId, olderCount, totalCount);
            }).subscribeOn(Schedulers.boundedElastic()).subscribe();
            // 本次使用旧摘要（没有则只用最近5条原始对话）
            summary = oldSummary;
            log.info("会话摘要待更新，本次使用旧摘要: sessionId={}, 总{}条", sessionId, totalCount);
        } else {
            summary = summaryRecord.getSummary();
            log.info("复用会话摘要: sessionId={}, 总{}条", sessionId, totalCount);
        }

        if (summary.isBlank()) return recentText;
        return "【历史对话摘要】\n" + summary + "\n\n【最近对话】\n" + recentText;
    }

    /**
     * 调用AI生成对话摘要
     */
    private String summarizeHistory(String historyText) {
        try {
            return chatClient.prompt()
                    .system("你是对话摘要助手，只输出摘要内容，不要任何解释、标题或Markdown格式。")
                    .user("请简要总结以下对话历史的核心内容，保留关键事实、用户需求和助手结论，不超过300字：\n\n" + historyText)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("对话摘要生成失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 保存或更新会话摘要
     */
    private void saveSummary(String sessionId, String summary, int totalCount) {
        try {
            ChatSessionSummary existing = this.summaryRepository.findBySessionId(sessionId).block();
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                ChatSessionSummary record = new ChatSessionSummary();
                record.setSessionId(sessionId);
                record.setSummary(summary);
                record.setLastMessageCount(totalCount);
                record.setCreatedTime(now);
                record.setUpdatedTime(now);
                this.summaryRepository.save(record).block();
            } else {
                existing.setSummary(summary);
                existing.setLastMessageCount(totalCount);
                existing.setUpdatedTime(now);
                this.summaryRepository.save(existing).block();
            }
        } catch (Exception e) {
            log.warn("保存会话摘要失败: {}", e.getMessage());
        }
    }

    /**
     * 保存一条对话记录（异步，不阻塞查询响应）
     *
     * @param request 用户提问
     */
    public Mono<Void> saveConversation(GraphRagRequest request, Long apiKeyId) {
        if (ObjectUtils.isEmpty(request.getQuestion())) {
            return Mono.empty();
        }
        if (ObjectUtils.isEmpty(request.getAnswer())) {
            request.setAnswer("");
        }
        JsonNode sourcesNode;
        if (StringUtils.hasLength(request.getSources())) {
            JsonNode parsed = null;
            try {
                parsed = ContextUtil.OBJECT_MAPPER.readTree(request.getSources());
            } catch (Exception e) {
                log.warn("解析来源JSON失败: {}", e.getMessage());
            }
            sourcesNode = parsed;
        } else {
            sourcesNode = null;
        }
        // 查询该会话已有的标题（取最新一条记录的title），新记录继承相同标题
        return this.chatMessageRepository.findRecentBySessionId(request.getSessionId(), 1, 0)
                .next().map(latest -> StringUtils.hasLength(latest.getTitle()) ? latest.getTitle() : "")
                .defaultIfEmpty("").flatMap(existingTitle -> {
                    ChatMessage record = ChatMessage.builder().userId(request.getUserId())
                            .sessionId(request.getSessionId()).title(existingTitle)
                            .question(request.getQuestion()).answer(request.getAnswer())
                            .reasoning(request.getReasoning()).sources(sourcesNode)
                            .tenantCode(request.getTenantCode()).systemType(request.getSystemType())
                            .apiKeyId(apiKeyId).createTime(LocalDateTime.now()).build();
                    return this.chatMessageRepository.save(record).doOnSuccess(r -> {
                        if (!ObjectUtils.isEmpty(r)) {
                            log.info("对话记录已保存: id={}, userId={}, sessionId={}, question={}", r.getId(), request.getUserId(), request.getSessionId(),
                                    request.getQuestion().length() > 50 ? request.getQuestion().substring(0, 50) + "..." : request.getQuestion());
                            // 首次问答完成且尚无标题时，异步生成会话主题
                            if (!StringUtils.hasLength(existingTitle)) {
                                generateSessionTitleAsync(request.getSessionId(), request.getQuestion(), request.getAnswer());
                            }
                        }
                    });
                }).then();
    }

    /**
     * 异步生成会话主题（首次问答后调用，生成后更新该会话所有记录的title，固定不变）
     */
    private void generateSessionTitleAsync(String sessionId, String firstQuestion, String firstAnswer) {
        Mono.fromRunnable(() -> {
            try {
                log.info("开始生成对话的主题！");
                // 计数检查（异步线程内block安全）：第一次问答完成后即生成标题
                Long count = this.chatMessageRepository.countBySessionId(sessionId).block();
                if (count != null && count > 1) {
                    return;
                }
                // 再次确认尚无标题
                ChatMessage latest = this.chatMessageRepository.findRecentBySessionId(sessionId, 1, 0).next().block();
                if (latest != null && latest.getTitle() != null && !latest.getTitle().isBlank()) {
                    return;
                }
                String title = this.chatClient.prompt()
                        .system("你是对话主题生成助手，只返回主题名称，不要任何解释、引号或标点，不超过15个字。")
                        .user("请根据以下对话生成一个简洁的主题名称：\n用户：" + firstQuestion + "\n助手：" + firstAnswer)
                        .call()
                        .content();
                if (title != null && !title.isBlank()) {
                    title = title.trim().replaceAll("[\"'`]", "").replaceAll("\\s+", " ");
                    if (title.length() > 30) title = title.substring(0, 30);
                    this.chatMessageRepository.updateTitleBySessionId(title, sessionId).block();
                    log.info("会话主题已生成并更新: sessionId={}, title={}", sessionId, title);
                }
            } catch (Exception e) {
                log.warn("生成会话主题失败: sessionId={}, {}", sessionId, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 按用户查询最近对话记录
     */
    public Mono<List<ChatMessage>> getConversations(String userId, String tenantCode,
                                                    String systemType, int limit) {
        return this.chatMessageRepository.findByUser(userId, tenantCode, systemType, limit)
                .collectList();
    }

    // ==================== 对话历史摘要压缩 ====================

    /**
     * 按会话ID分页查询对话
     * order=desc（默认，倒序）；order=asc（正序，导出用）
     */
    public Mono<List<ChatMessage>> getConversationsBySession(String sessionId, int limit, int offset, String order) {
        if ("asc".equalsIgnoreCase(order)) {
            return this.chatMessageRepository.findBySessionIdAsc(sessionId, limit, offset).collectList();
        }
        return this.chatMessageRepository.findRecentBySessionId(sessionId, limit, offset).collectList();
    }

    /**
     * 查询用户的会话列表（取每个会话最后一条记录的title作为标题，按最后活跃时间倒序）
     */
    public Mono<List<Map<String, Object>>> getSessions(String userId, String tenantCode,
                                                       String systemType, int limit) {
        return this.chatMessageRepository.findByUser(userId, tenantCode, systemType, 500)
                .collectList().map(records -> aggregateSessions(records, limit));
    }

    /**
     * 按 API Key 查询会话列表（该 Key 创建的对话，可按用户/租户/系统过滤，条件为空不过滤）
     */
    public Mono<List<Map<String, Object>>> getSessionsByApiKey(Long apiKeyId, String userId,
                                                               String tenantCode, String systemType, Pageable pageable) {
        return this.chatMessageRepository.findByApiKey(apiKeyId,
                        blankToNull(userId), blankToNull(tenantCode), blankToNull(systemType))
                .collectList().map(records -> aggregateSessions(records, pageable.getPageSize()));
    }

    // ==================== 对话记录 ====================

    /**
     * 按会话ID + API Key 查询对话消息（校验归属：仅该 Key 创建的消息）
     */
    public Mono<List<ChatMessage>> getConversationsBySessionAndApiKey(String sessionId, Long apiKeyId) {
        return chatMessageRepository.findBySessionIdAndApiKey(sessionId, apiKeyId).collectList();
    }

    /**
     * 删除单个会话（仅当该会话存在属于该 Key 的消息才删除；删除后若会话无剩余消息，同步清理摘要）
     *
     * @return true=已删除；false=该会话不属于此 API Key，未做任何改动
     */
    public Mono<Boolean> deleteSessionByApiKey(String sessionId, Long apiKeyId) {
        return chatMessageRepository.countBySessionIdAndApiKey(sessionId, apiKeyId).flatMap(count -> {
            if (count == 0) {
                return Mono.just(false);
            }
            return chatMessageRepository.deleteBySessionIdAndApiKey(sessionId, apiKeyId)
                    .then(chatMessageRepository.countBySessionId(sessionId))
                    .flatMap(remain -> remain == 0 ? summaryRepository
                            .deleteBySessionId(sessionId) : Mono.empty())
                    .thenReturn(true);
        });
    }

    /**
     * 批量删除会话（仅删除属于该 Key 的会话，返回实际删除数）
     */
    public Mono<Integer> deleteSessionsByApiKey(List<String> sessionIds, Long apiKeyId) {
        if (ObjectUtils.isEmpty(sessionIds)) {
            return Mono.just(0);
        }
        return Flux.fromIterable(sessionIds)
                .flatMap(id -> deleteSessionByApiKey(id, apiKeyId))
                .filter(Boolean::booleanValue).count().map(Long::intValue);
    }

    /**
     * 会话列表聚合：取每个会话最后一条记录的 title（空则用首条问题兜底），按最后活跃时间倒序
     */
    private List<Map<String, Object>> aggregateSessions(List<ChatMessage> records, int limit) {
        // findByUser 是 DESC，lastBySession 取最近一条（含title），同时记录首条问题作兜底
        Map<String, ChatMessage> lastBySession = new LinkedHashMap<>();
        Map<String, String> firstQuestionBySession = new HashMap<>();
        Map<String, Boolean> seen = new HashMap<>();
        // 正序遍历找每个session的首条问题
        for (int i = records.size() - 1; i >= 0; i--) {
            ChatMessage r = records.get(i);
            if (!seen.containsKey(r.getSessionId())) {
                seen.put(r.getSessionId(), true);
                firstQuestionBySession.put(r.getSessionId(), r.getQuestion());
            }
        }
        for (ChatMessage r : records) {
            if (!lastBySession.containsKey(r.getSessionId())) {
                lastBySession.put(r.getSessionId(), r);
            }
        }
        return lastBySession.entrySet().stream().limit(limit).map(e -> {
            ChatMessage r = e.getValue();
            String title = r.getTitle();
            if (ObjectUtils.isEmpty(title)) {
                title = firstQuestionBySession.get(r.getSessionId());
            }
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("sessionId", r.getSessionId());
            m.put("title", title);
            m.put("question", title);
            m.put("createTime", r.getCreateTime());
            m.put("userId", r.getUserId());
            return m;
        }).collect(Collectors.toList());
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * 更新会话标题（批量更新该会话所有记录的title）
     */
    public Mono<Integer> updateSessionTitle(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            return Mono.just(0);
        }
        return chatMessageRepository.updateTitleBySessionId(title.trim(), sessionId)
                .doOnSuccess(cnt -> log.info("会话标题已更新: sessionId={}, title={}, 影响行数={}", sessionId, title, cnt));
    }

    /**
     * 删除单个会话（该会话下所有对话记录 + 摘要）
     */
    public Mono<Void> deleteSession(String sessionId) {
        return chatMessageRepository.deleteBySessionId(sessionId)
                .then(summaryRepository.deleteBySessionId(sessionId))
                .doOnSuccess(_ -> log.info("已删除会话及摘要: sessionId={}", sessionId));
    }

    /**
     * 批量删除多个会话（对话记录 + 摘要）
     */
    public Mono<Void> deleteSessions(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) return Mono.empty();
        return Flux.fromIterable(sessionIds)
                .flatMap(id -> chatMessageRepository.deleteBySessionId(id)
                        .then(summaryRepository.deleteBySessionId(id)))
                .then().doOnSuccess(_ -> log.info("已批量删除 {} 个会话及摘要", sessionIds.size()));
    }

    /**
     * AI 返回的实体+关系抽取结果结构
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntityExtractionResult(List<ExtractedEntity> entities, List<ExtractedRelation> relations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractedEntity(String name, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ExtractedRelation(String source, String target, String type) {
    }

    /**
     * SSE 流式事件：type=reasoning 表示思考片段，type=content 表示正文片段。
     * 通过 type 字段区分思考/正文，前端不依赖内容是否为空判断。
     */
    public record ChatStreamEvent(String type, String text) {
    }

    /**
     * 查询结果封装：知识库来源 + SSE 事件流（思考/正文通过 type 字段区分）
     */
    public record QueryResult(List<Map<String, Object>> sources, Flux<ChatStreamEvent> answer) {
    }

    /**
     * 内部上下文封装
     */
    private record ContextResult(String fullContext, List<Map<String, Object>> sources, String historyContext) {
    }

}
