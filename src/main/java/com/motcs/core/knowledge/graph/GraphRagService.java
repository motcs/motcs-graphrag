package com.motcs.core.knowledge.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import com.motcs.commons.ContextUtil;
import com.motcs.commons.annotation.RestServerException;
import com.motcs.commons.base.DatabaseService;
import com.motcs.core.auth.keys.usage.record.ChatUsageRecord;
import com.motcs.core.auth.keys.usage.record.ChatUsageRecordRepository;
import com.motcs.core.auth.keys.usage.session.ChatSessionUsageRepository;
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
import com.motcs.core.knowledge.record.session.ChatSession;
import com.motcs.core.knowledge.record.session.ChatSessionRepository;
import com.motcs.core.knowledge.record.session.ChatSessionRequest;
import com.motcs.core.knowledge.record.unanswered.UnansweredQuestion;
import com.motcs.core.knowledge.record.unanswered.UnansweredQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.huaban.analysis.jieba.JiebaSegmenter.SegMode.SEARCH;

/**
 * GraphRAG知识库服务
 * 结合向量检索和图谱检索实现高质量知识问答
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class GraphRagService extends DatabaseService {

    /**
     * 摘要时保留最近N轮原始对话
     */
    private static final int HISTORY_KEEP_RECENT = 10;

    /**
     * 本地知识库无法回复时，第三方 AI 直接回答的 Prompt（不依赖知识库上下文）
     */
    private static final String THIRD_PARTY_DIRECT_PROMPT = """
            你是企业知识库问答助手。用户咨询的问题在当前数据库中暂无相关业务介绍。
            请按以下要求回答：
            1. 回答开头先提示：数据库暂无相关业务介绍，我现在联网搜索介绍。
            2. 然后基于自身通用知识与能力，自主、认真地回答用户的问题，给出有价值、可操作的内容；尽量不要说"不知道"或"无法回答"，不要敷衍。
            3. 若确实超出能力范围，也应尽力给出方向性建议或相关背景信息，而不是直接拒绝。
            4. 回复使用 标准的Markdown 格式，简洁友好。
            """;

    /**
     * 本地无命中且判断为闲聊/历史可答时的 Prompt（自然回复，不记录）
     */
    private static final String NO_RESULT_SMALLTALK_PROMPT = """
            你是企业知识库问答助手，请根据历史对话理解用户意图。
            1. 如果是打招呼、问候、闲聊（如"你好"、"谢谢"、"你是谁"等），请礼貌、自然地回复，不要提及知识库。
            2. 如果历史对话中已存在用户要求重新回答、追问的问题及对应回答，请直接基于历史对话中的原回答重新组织语言作答，不要复述"知识库无相关内容"。
            3. 你主要能力：分析文档，通过私有知识库回答问题。
            4. 回复使用 标准的Markdown 格式，简洁友好，通常建议不超过100字。
            """;
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
     * 本地中文分词器（查询词优化专用）：毫秒级、零远程调用、永不失败
     */
    private static final JiebaSegmenter JIEBA_SEGMENTER = new JiebaSegmenter();
    /**
     * 查询词优化要去除的噪声词（语气词/疑问词/人称/助词/介词/量词）。
     * 注意：不放"流程/功能/制度"等有业务含义的词，避免误删关键检索词。
     */
    private static final Set<String> REWRITE_NOISE = Set.of(
            "请问", "怎么", "如何", "什么", "哪些", "哪个", "多少", "为什么", "是否", "是不是",
            "能否", "可以", "能", "请", "帮", "咱", "咱们", "我们", "你们", "他们", "它",
            "的吗", "呢", "吧", "啊", "么", "啥", "的", "了", "是", "有", "都", "要", "想",
            "在", "与", "和", "及", "或", "一下", "个", "种", "上", "下", "里面", "相关", "当中");
    /**
     * 语义相关性过滤下限：向量相似度分数低于该值视为"语义无关"文档，不返回给前端。
     * 0.5 偏松会把仅共用一个泛词（如"职责"）的文档误召回，取 0.6 作为语义分界（可调）。
     */
    private static final double DEFAULT_SEMANTIC_THRESHOLD = 0.85;
    /**
     * 系统提示词（含多轮历史和知识库上下文）
     */
    private static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手，请优先依据下面提供的知识库上下文回答用户问题。
            如果知识库上下文没有相关内容，但【历史对话】中已存在用户要求重新回答的问题及对应回答，请直接基于历史对话中的原回答重新组织语言作答，不要复述"知识库无相关内容"。
            若知识库上下文与用户问题不相关或不足以准确回答，请先提示：数据库暂无相关业务介绍，我现在联网搜索介绍；然后基于自身通用知识与能力自主回答，给出有价值、可操作的内容，坚决不要说"不知道""无法回答""没有检索到相关内容"等话术，不要编造信息。
            
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
    private final UnansweredQuestionRepository unansweredQuestionRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionSummaryRepository summaryRepository;
    private final ChatUsageRecordRepository chatUsageRecordRepository;
    private final ChatSessionUsageRepository chatSessionUsageRepository;
    @Value("${app.ai.provider:baidu}")
    private String provider;
    /**
     * 初始召回候选数（Top-N 召回，默认 20，精排后再取最终 topK）
     */
    @Value("${app.ai.recall.top-k:20}")
    private int recallTopK;
    /**
     * 召回阶段相似度阈值（默认 0.0=不过滤，交给精排阶段过滤，便于观察完整分数分布）
     */
    @Value("${app.ai.recall.threshold:0.0}")
    private double recallThreshold;
    /**
     * 外部精排服务（可选）；未配置任何 RerankerService 实现时用本地精排兜底
     */
    @Autowired(required = false)
    private RerankerService rerankerService;

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
        log.debug("收到GraphRAG查询: question={}, userId={}, sessionId={}, tenant={}, system={}",
                ragQuery.getQuestion(), ragQuery.getUserId(), ragQuery.getSessionId(),
                ragQuery.getTenantCode(), ragQuery.getSystemType());
        return buildHistoryContext(ragQuery.getSessionId()).map(historyContext -> {
            // 1. 本地 Jieba 分词改写查询词（毫秒级、零远程、不失败），首词作为展示用改写词
            List<String> queries = rewriteQuery(ragQuery.getQuestion());
            String rewriteDisplay = queries.getFirst();
            // 2. 惰性完整事件流：订阅时按后端实际执行顺序逐步 emit 各阶段（见 buildStream）
            AtomicReference<Usage> usageRef = new AtomicReference<>();
            Flux<ChatStreamEvent> answer = buildStream(ragQuery, queries, rewriteDisplay, historyContext, usageRef);
            // sources 由事件流中 type=sources 事件携带（controller 从流收集，顺序在精排后）
            return new QueryResult(List.of(), answer, usageRef, rewriteDisplay);
        }).doOnError(e -> log.error("GraphRAG查询出错: {}", e.getMessage(), e));
    }

    /**
     * 惰性完整事件流：订阅时按实际执行顺序逐步下发。
     * 事件顺序：查询词优化 → 向量检索 → 相关度精排 → sources(引用) → 推理补全 → 图谱召回 → 回答(reasoning/content)。
     * 每个 Flux.defer 段在订阅到它时才执行，从而"每操作一步下发一条"，前端可滚动实时看到后端在做什么。
     */
    private Flux<ChatStreamEvent> buildStream(GraphRagRequest ragQuery, List<String> queries, String rewriteDisplay,
                                              String historyContext, AtomicReference<Usage> usageRef) {
        return Flux.concat(
                Flux.just(new ChatStreamEvent("stage", "查询词优化：已完成")),
                Flux.defer(() -> {
                    List<Document> vectorDocs;
                    try {
                        vectorDocs = vectorRetrieve(ragQuery, queries);
                    } catch (Exception e) {
                        log.error("向量检索失败: {}", e.getMessage(), e);
                        return Flux.concat(
                                Flux.just(new ChatStreamEvent("stage_error", "向量检索：失败")),
                                buildNoResultStream(ragQuery, historyContext, rewriteDisplay, usageRef));
                    }
                    return Flux.concat(
                            Flux.just(new ChatStreamEvent("stage", "向量检索：命中 " + vectorDocs.size() + " 条候选文档")),
                            Flux.defer(() -> {
                                List<Document> reranked;
                                try {
                                    reranked = rerankDocs(ragQuery, queries, vectorDocs);
                                } catch (Exception e) {
                                    log.error("相关度精排失败: {}", e.getMessage(), e);
                                    return Flux.concat(
                                            Flux.just(new ChatStreamEvent("stage_error", "相关度精排：失败")),
                                            buildNoResultStream(ragQuery, historyContext, rewriteDisplay, usageRef));
                                }
                                if (reranked.isEmpty()) {
                                    return Flux.concat(
                                            Flux.just(new ChatStreamEvent("stage", "相关度精排：无相关文档（低于阈值）")),
                                            buildNoResultStream(ragQuery, historyContext, rewriteDisplay, usageRef));
                                }
                                return Flux.concat(
                                        Flux.just(new ChatStreamEvent("stage", "相关度精排：保留 " + reranked.size() + " 条")),
                                        buildFromReranked(ragQuery, reranked, historyContext, rewriteDisplay, usageRef));
                            }));
                }));
    }

    /**
     * 精排命中后的后续链路：提取来源 → 推理上下文补全 → 图谱召回 → 可回答性判断 → 回答。
     * 各步骤独立 try-catch：失败发 stage_error（前端标红）并用降级值继续，不中断整个 SSE 流。
     */
    private Flux<ChatStreamEvent> buildFromReranked(GraphRagRequest ragQuery, List<Document> reranked,
                                                    String historyContext, String rewriteDisplay, AtomicReference<Usage> usageRef) {
        return Flux.defer(() -> {
            List<Map<String, Object>> sources = extractSourcesQuietly(reranked);
            return buildCoarseGraphAnswer(ragQuery, reranked, sources, historyContext, rewriteDisplay, usageRef);
        });
    }

    /**
     * 提取来源（失败静默降级为空，不影响后续）。
     */
    private List<Map<String, Object>> extractSourcesQuietly(List<Document> reranked) {
        try {
            return extractSources(reranked);
        } catch (Exception e) {
            log.error("提取来源失败: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 推理上下文补全（失败发 stage_error 并降级为空）→ 图谱召回与回答。
     */
    private Flux<ChatStreamEvent> buildCoarseGraphAnswer(GraphRagRequest ragQuery, List<Document> reranked, List<Map<String, Object>> sources,
                                                         String historyContext, String rewriteDisplay, AtomicReference<Usage> usageRef) {
        return Flux.defer(() -> {
            String coarseContext;
            try {
                coarseContext = buildCoarseContext(reranked);
            } catch (Exception e) {
                log.error("推理上下文补全失败: {}", e.getMessage(), e);
                coarseContext = "";
                return Flux.concat(
                        Flux.just(new ChatStreamEvent("stage_error", "推理上下文补全：失败")),
                        buildGraphAndAnswer(ragQuery, reranked, sources, coarseContext, historyContext, rewriteDisplay, usageRef));
            }
            return Flux.concat(
                    Flux.just(new ChatStreamEvent("stage", "推理上下文补全：已完成")),
                    buildGraphAndAnswer(ragQuery, reranked, sources, coarseContext, historyContext, rewriteDisplay, usageRef));
        });
    }

    /**
     * 图谱多跳召回 + 回答分支。图谱召回失败时降级为空，不影响后续回答。
     */
    private Flux<ChatStreamEvent> buildGraphAndAnswer(GraphRagRequest ragQuery, List<Document> reranked,
                                                      List<Map<String, Object>> sources, String coarseContext,
                                                      String historyContext, String rewriteDisplay, AtomicReference<Usage> usageRef) {
        return Flux.defer(() -> {
            List<GraphRagResult> multiHop;
            try {
                multiHop = graphRecall(reranked, ragQuery);
            } catch (Exception e) {
                log.error("图谱多跳召回失败: {}", e.getMessage(), e);
                multiHop = List.of();
                return Flux.concat(
                        Flux.just(new ChatStreamEvent("stage_error", "图谱多跳召回：失败")),
                        buildHitAnswerStream(ragQuery, assembleContextFinal(reranked, sources, coarseContext, multiHop, historyContext),
                                rewriteDisplay, usageRef, sources));
            }
            return Flux.concat(
                    Flux.just(new ChatStreamEvent("stage", "图谱多跳召回：" + multiHop.size() + " 个关联块")),
                    buildHitAnswerStream(ragQuery, assembleContextFinal(reranked, sources, coarseContext, multiHop, historyContext),
                            rewriteDisplay, usageRef, sources));
        });
    }

    /**
     * 检索命中分支：先做可回答性判断，能答走知识库回答，不能答转第三方 AI 并记录；阶段事件逐步下发。
     */
    private Flux<ChatStreamEvent> buildHitAnswerStream(GraphRagRequest ragQuery, ContextResult ctx, String rewriteDisplay,
                                                       AtomicReference<Usage> usageRef, List<Map<String, Object>> sources) {
        return Flux.defer(() -> {
            boolean can;
            try {
                can = canAnswerWithContext(ragQuery.getQuestion(), ctx.fullContext());
            } catch (Exception e) {
                log.error("可回答性判断失败: {}", e.getMessage(), e);
                // 判断失败：保守转第三方直接回答，不展示引用
                ragQuery.setUnanswered(true);
                ChatClient.ChatClientRequestSpec thirdSpec = this.chatClient.prompt()
                        .system(s -> s.text(THIRD_PARTY_DIRECT_PROMPT).param("history", ctx.historyContext()))
                        .user(ragQuery.getQuestion());
                applyModelOptions(thirdSpec, ragQuery.getModel(), ragQuery.getEnableThinking());
                return Flux.concat(Flux.just(new ChatStreamEvent("stage_error", "可回答性判断：失败，转联网/第三方")),
                        streamChatEvents(thirdSpec, usageRef));
            }
            if (!can) {
                // 标记本次本地无法回复，回答完成后由 controller 统一落库；不展示引用
                ragQuery.setUnanswered(true);
                ChatClient.ChatClientRequestSpec thirdSpec = this.chatClient.prompt()
                        .system(s -> s.text(THIRD_PARTY_DIRECT_PROMPT).param("history", ctx.historyContext()))
                        .user(ragQuery.getQuestion());
                applyModelOptions(thirdSpec, ragQuery.getModel(), ragQuery.getEnableThinking());
                return Flux.concat(Flux.just(new ChatStreamEvent("stage", "可回答性判断：知识库不充分，转联网/第三方")),
                        streamChatEvents(thirdSpec, usageRef));
            }
            ChatClient.ChatClientRequestSpec answerSpec = this.chatClient.prompt()
                    .system(s -> s.text(SYSTEM_PROMPT).param("context", ctx.fullContext())
                            .param("history", ctx.historyContext())).user(ragQuery.getQuestion());
            applyModelOptions(answerSpec, ragQuery.getModel(), ragQuery.getEnableThinking());
            return Flux.concat(Flux.just(new ChatStreamEvent("stage", "可回答性判断：知识库可回答")),
                    // 引用在可回答性判断之后下发，回答开始前展示
                    Flux.just(new ChatStreamEvent("sources", toJsonArray(sources))),
                    streamChatEvents(answerSpec, usageRef));
        });
    }

    /**
     * 检索无结果分支：分类闲聊/历史可答（自然回复）或知识问题（转第三方 AI 并记录）。
     */
    private Flux<ChatStreamEvent> buildNoResultStream(GraphRagRequest ragQuery, String historyContext, String rewriteDisplay,
                                                      AtomicReference<Usage> usageRef) {
        return Flux.defer(() -> {
            boolean knowledge = isKnowledgeQuestion(ragQuery.getQuestion(), historyContext);
            String prompt = knowledge ? THIRD_PARTY_DIRECT_PROMPT : NO_RESULT_SMALLTALK_PROMPT;
            if (knowledge) {
                // 标记本次本地无法回复，回答完成后由 controller 统一落库
                ragQuery.setUnanswered(true);
            }
            ChatClient.ChatClientRequestSpec noResultSpec = this.chatClient.prompt()
                    .system(s -> s.text(prompt).param("context", "无").param("history", historyContext))
                    .user(ragQuery.getQuestion());
            applyModelOptions(noResultSpec, ragQuery.getModel(), ragQuery.getEnableThinking());
            return streamChatEvents(noResultSpec, usageRef);
        });
    }

    /**
     * 检索命中分支：组装回答 SSE 流
     */
    /**
     * 快模型：用于改写/闲聊与可回答性判断，不跟随用户选择的重型思考模型
     */
    private void applyFastModel(ChatClient.ChatClientRequestSpec spec) {
        if (Objects.equals(provider, "baidu")) {
            spec.options(OpenAiChatOptions.builder().model("ernie-4.5-turbo-20260402"));
        } else if (Objects.equals(provider, "zhipu")) {
            spec.options(OpenAiChatOptions.builder().model("glm-4-flash"));
        } else {
            spec.options(OpenAiChatOptions.builder().model("deepseek-v3.2"));
        }
    }

    /**
     * 判断本地检索无命中时，用户问题是闲聊/历史可答，还是需要知识回答的知识问题。
     * 返回 true = 知识问题（本地无法回复，需转第三方 AI 并记录）。
     * 判断失败时保守按知识问题处理。
     */
    private boolean isKnowledgeQuestion(String question, String historyContext) {
        try {
            String judgePrompt = """
                    你是企业知识库问答的问题分类器。结合历史对话判断用户当前问题属于哪一类：
                    1. 闲聊问候（你好、谢谢、你是谁、在吗、打招呼等）→ 输出：闲聊
                    2. 历史对话中已包含该问题的明确回答（用户是在要求重新回答、追问或引用之前内容）→ 输出：历史可答
                    3. 需要知识或业务内容回答的知识/业务问题 → 输出：知识问题
                    只输出三个词之一，不要解释。""";
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .system(judgePrompt)
                    .user("【历史对话】\n" + (StringUtils.hasLength(historyContext) ? historyContext : "无")
                            + "\n\n【当前问题】" + question);
            applyFastModel(spec);
            String text = spec.call().content();
            return text != null && text.contains("知识问题");
        } catch (Exception e) {
            log.warn("问题分类判断失败，按知识问题处理: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 判断给定知识库上下文能否准确回答该问题。返回 true = 能依据上下文准确回答。
     * 判断失败时保守按能回答处理（走原知识库回答，避免误转第三方 AI）。
     */
    private boolean canAnswerWithContext(String question, String context) {
        try {
            String judgePrompt = """
                    你是严谨的判断器。判断提供的知识库上下文是否包含与用户问题【直接相关】的实质性内容，从而能够准确回答用户问题。
                    判定规则：
                    - 上下文包含与用户问题直接相关、足以准确回答的实质内容 → 输出：能回答
                    - 上下文与用户问题无关、不相关、仅泛泛相关但不足以准确回答、或上下文为空 → 输出：不能回答
                    请严格比对问题与上下文主题是否一致。例如：用户问"数智统战平台包含哪些功能"，而上下文中只有"人大代表职责"相关内容，属于明显无关，输出：不能回答。
                    宁可保守判定"不能回答"，也不要误判"能回答"。
                    只输出四个字：能回答 或 不能回答，不要解释。""";
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt().system(judgePrompt)
                    .user("【知识库上下文】\n%s\n\n【用户问题】%s".formatted(context, question));
            applyFastModel(spec);
            String text = spec.call().content();
            return text != null && text.contains("能回答");
        } catch (Exception e) {
            log.warn("可回答性判断失败，按不能回答处理，转第三方AI并记录: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 记录"本地知识库无法回复"的问题（异步，不阻塞回答响应）
     */
    private void recordUnanswered(GraphRagRequest ragQuery) {
        try {
            UnansweredQuestion rec = UnansweredQuestion.builder()
                    .question(ragQuery.getQuestion())
                    .answer(ragQuery.getAnswer())
                    .reasoning(ragQuery.getReasoning())
                    .sources(ragQuery.getSources() != null ? ragQuery.getSources().toString() : null)
                    .userId(ragQuery.getUserId())
                    .sessionId(ragQuery.getSessionId())
                    .tenantCode(ragQuery.getTenantCode())
                    .systemType(ragQuery.getSystemType())
                    .status("OPEN")
                    .createTime(LocalDateTime.now())
                    .build();
            unansweredQuestionRepository.save(rec)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            r -> log.debug("记录本地无法回复问题: id={}, question={}", r.getId(), truncate(ragQuery.getQuestion())),
                            e -> log.warn("记录本地无法回复问题失败: {}", e.getMessage()));
        } catch (Exception e) {
            log.warn("记录本地无法回复问题异常: {}", e.getMessage());
        }
    }

    private String truncate(String s) {
        return s == null ? "" : (s.length() > 50 ? s.substring(0, 50) + "..." : s);
    }

    /**
     * 统一落库"本地无法回复"问题：优先依据服务层标记（unanswered=true），
     * 同时兜底检查知识库回答路径是否含"无法回复"话术。
     * 由 controller 在回答完成后调用，此时 request 已含完整回答与思考过程。
     */
    public Mono<Void> recordUnansweredIfApplicable(GraphRagRequest request) {
        boolean flagged = Boolean.TRUE.equals(request.getUnanswered());
        var sources = request.getSources();
        boolean hasSource = sources != null && sources.isArray() && !sources.isEmpty();
        if (!flagged && (!hasSource || !containsNegativeAnswer(request.getAnswer()))) {
            return Mono.empty();
        }
        log.debug("记录本地无法回复问题: {}", truncate(request.getQuestion()));
        recordUnanswered(request);
        return Mono.empty();
    }

    private boolean containsNegativeAnswer(String answer) {
        if (!StringUtils.hasLength(answer)) {
            return false;
        }
        String[] markers = {"暂无相关业务", "数据库暂无", "没有检索到", "没有找到", "未检索到", "未找到",
                "无法回答", "无法提供", "暂无相关", "没有相关内容", "没有查询到", "没有收录"};
        for (String m : markers) {
            if (answer.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检索查询词优化：本地 Jieba 分词 + 规则组装，毫秒级完成、不依赖远程、永不失败。
     *
     * @return 查询词列表（首个为展示用改写词），至少包含原始问题
     */
    private List<String> rewriteQuery(String question) {
        List<String> queries = buildLocalQueries(question);
        if (ObjectUtils.isEmpty(queries)) queries = List.of(question);
        log.debug("检索查询词优化(本地分词): {} → {}", question, queries);
        return queries;
    }

    /**
     * 本地查询词组装：原问题 + 去语气词版 + Jieba 关键词短语，最多 3 个
     */
    private List<String> buildLocalQueries(String question) {
        String raw = question == null ? "" : question.trim();
        if (raw.isEmpty()) {
            if (question != null) {
                return List.of(question);
            }
            return List.of();
        }

        // 1) 去首尾语气/疑问词的标准查询词；注意"么"是"什么/怎么/为什么"的构词语素，绝不能删
        String standard = raw.replaceAll("^(请问|能不能|可否|能否|能|请|麻烦|帮我|帮|我想|我需要|可以告诉我)+", "")
                .replaceAll("[？?！!。.，,、；;：:\"'\\s]+$", "")
                .replaceAll("[呢吗吧啊呀嘛哦]$", "").trim();
        if (standard.isEmpty()) standard = raw;

        // 2) Jieba 分词（SEARCH 模式），剔除噪声词与单字，保留有信息量的词
        List<String> keywords = new ArrayList<>();
        for (SegToken tok : JIEBA_SEGMENTER.process(raw, SEARCH)) {
            String w = tok.word.trim();
            if (w.length() < 2 || REWRITE_NOISE.contains(w)) continue;
            if (!keywords.contains(w)) keywords.add(w);
        }
        if (keywords.isEmpty()) keywords.add(standard);

        // 3) 组装查询词：标准版 + 原问题 + 关键词组合，去重限 3 个
        List<String> queries = new ArrayList<>();
        queries.add(standard);
        if (!queries.contains(raw)) queries.add(raw);
        String joined = String.join(" ", keywords);
        if (!queries.contains(joined)) queries.add(joined);
        if (keywords.size() >= 2) {
            String focus = String.join(" ", keywords.subList(0, Math.min(3, keywords.size())));
            if (!queries.contains(focus)) queries.add(focus);
        }
        List<String> result = queries.stream().filter(StringUtils::hasLength).distinct().limit(3).toList();
        return ObjectUtils.isEmpty(result) ? List.of(raw) : result;
    }

    /**
     * 按用户前端选择的模型动态覆盖 ChatClient 请求（per-request options），
     * 不传或空白时使用配置文件里的默认模型。
     */
    private void applyModelOptions(ChatClient.ChatClientRequestSpec spec, String model, boolean enableThinking) {
        if (StringUtils.hasLength(model)) {
            OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model(model);
            // 思考开关：开启 thinking.type=enabled，关闭 thinking.type=disabled（默认关闭）
            builder.extraBody(Map.of("thinking", Map.of("type", enableThinking ? "enabled" : "disabled")));
            spec.options(builder);
            log.debug("本次问答使用用户选择模型: {}, 思考: {}", model, enableThinking ? "开启" : "关闭");
        }
    }

    /**
     * 将 Spring AI 流式响应转换为 SSE 事件流。
     * 每个 chunk 可能携带思考片段（metadata["reasoningContent"]）或回答片段（getText()），
     * 思考片段输出 type=reasoning，正文片段输出 type=content（仅当正文实际有文本时下发，
     * 思考阶段 getText() 为空，不发送空 content 事件，避免前端误判为"正文开始"而折叠思考框）。
     */
    private Flux<ChatStreamEvent> streamChatEvents(ChatClient.ChatClientRequestSpec promptSpec, AtomicReference<Usage> usageRef) {
        // Spring AI 2.0.1 每个流式 chunk 的 metadata["reasoningContent"] 是累计值，
        // 这里只取新增片段下发，避免前端重复拼接。
        AtomicReference<String> lastReasoning = new AtomicReference<>("");
        return promptSpec.stream().chatResponse().concatMap(response -> {
            // 累计 token 用量：每个 chunk 的 metadata 都带 Usage（总用量），流结束时保留最后一份
            Usage usage = response.getMetadata().getUsage();
            if (!ObjectUtils.isEmpty(usageRef)) {
                usageRef.set(usage);
            }
            if (ObjectUtils.isEmpty(response.getResult())) {
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
            if (StringUtils.hasLength(text)) {
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
     * 提取文本的主题词（Jieba 分词，忽略噪声词与泛词，只保留真实主题实体）
     */
    private Set<String> extractTopicWords(String text) {
        if (!StringUtils.hasLength(text)) return Set.of();
        Set<String> topics = new HashSet<>();
        for (SegToken tok : JIEBA_SEGMENTER.process(text, JiebaSegmenter.SegMode.SEARCH)) {
            String w = tok.word.trim();
            if (w.length() >= 2 && !REWRITE_NOISE.contains(w)) {
                topics.add(w);
            }
        }
        return topics;
    }

    /**
     * 合并多个查询词的主题词
     */
    private Set<String> extractQueryTopics(List<String> queries) {
        Set<String> topics = new HashSet<>();
        for (String q : queries) {
            topics.addAll(extractTopicWords(q));
        }
        return topics;
    }

    /**
     * 主题一致性判定（不依赖任何预设泛词表，自适应）：文档主题词与查询主题词
     * 重叠 ≥2 个词，或查询主题词很少（≤2）且全部命中，才视为主题相关。
     * 查询/文档无可判定主题词时视为通过（交给语义分数）。
     * 用于拦截 embedding 高分但主题不同的误召回（如"党建职责"vs"人大职责"仅共享极少数通用词）。
     */
    private boolean topicOverlap(Document doc, Set<String> queryTopics) {
        if (queryTopics.isEmpty()) return true;
        Set<String> docTopics = extractTopicWords(doc.getText());
        if (docTopics.isEmpty()) return true;
        int overlap = 0;
        for (String t : queryTopics) {
            if (docTopics.contains(t)) overlap++;
        }
        if (overlap >= 2) return true;
        return queryTopics.size() <= 2 && overlap == queryTopics.size();
    }

    /**
     * 本地精排兜底：语义分数达标 + 主题一致性过滤，按相似度降序取最相关的 topN 条。
     * 未配置外部 Reranker 时使用。
     *
     * @param semanticThreshold 精排阶段语义分数下限（低于视为无关）
     */
    private List<Document> localRerank(List<Document> docs, List<String> queries, int topN, double semanticThreshold) {
        Set<String> queryTopics = extractQueryTopics(queries);
        List<Document> kept = docs.stream()
                .filter(doc -> {
                    Double score = doc.getScore();
                    // 语义分数达标（或不可判）且主题一致（或无法主题判定）才保留
                    boolean semanticOk = score == null || score >= semanticThreshold;
                    return semanticOk && topicOverlap(doc, queryTopics);
                })
                // 相似度分数从高到低排序（无分数排最后）
                .sorted(Comparator.comparingDouble((Document d) ->
                        d.getScore() != null ? -d.getScore() : Double.MAX_VALUE))
                .limit(topN)
                .toList();
        if (kept.size() != docs.size()) {
            log.debug("本地精排剔除 {} 个无关文档，保留 {} 个（阈值 {}）",
                    docs.size() - kept.size(), kept.size(), semanticThreshold);
        }
        return kept;
    }

    /**
     * 步骤1：向量检索（按租户/系统/启用状态过滤 + 多查询词去重召回）。
     * 租户0 = 超管上传的全局默认文档，对所有租户开放；查询租户为 0 时不过滤租户。
     * 召回阶段：Top-K 候选 + 低阈值（默认不过滤），尽量召回交给精排阶段过滤。
     */
    private List<Document> vectorRetrieve(GraphRagRequest request, List<String> queries) {
        if (!StringUtils.hasLength(request.getTenantCode())) {
            throw RestServerException.withMsg("租户编码必填");
        } else if (!StringUtils.hasLength(request.getSystemType())) {
            throw RestServerException.withMsg("系统类型必填");
        }
        String filterExpr = getFilterExpr(request);
        log.debug("搜索条件：{}", filterExpr);
        List<Document> vectorDocs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String q : queries) {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(q).topK(recallTopK)
                    .similarityThreshold(recallThreshold)
                    .filterExpression(filterExpr).build();
            List<Document> docs = this.vectorStore.similaritySearch(searchRequest);
            for (Document doc : docs) {
                String key = doc.getMetadata().getOrDefault("docCode", "")
                        + "|" + doc.getMetadata().getOrDefault("chunkIndex", "");
                if (!seen.add(key)) continue;
                vectorDocs.add(doc);
            }
        }
        return vectorDocs;
    }

    /**
     * 步骤2：精排（优先外部 Reranker，否则本地精排）+ 租户自定义优先排序。
     * 仅靠分数不可靠（embedding 会把"人大代表职责"与"党建职责"判成高分），需再验证主题一致；
     * 精排后为空则视为未检索到。
     */
    private List<Document> rerankDocs(GraphRagRequest request, List<String> queries, List<Document> vectorDocs) {
        if (vectorDocs.isEmpty()) {
            return vectorDocs;
        }
        final int finalTopN = Math.max(1, request.getTopK());
        String primaryQuery = queries.getFirst();
        double semanticThreshold = Math.max(request.getThreshold(), DEFAULT_SEMANTIC_THRESHOLD);
        try {
            vectorDocs = (rerankerService != null)
                    ? rerankerService.rerank(primaryQuery, vectorDocs, finalTopN)
                    : localRerank(vectorDocs, queries, finalTopN, semanticThreshold);
        } catch (Exception e) {
            // 外部 Reranker 调用失败/超时：回退本地精排，保证检索不中断
            log.warn("外部 Reranker 调用失败，回退本地精排: {}", e.getMessage());
            vectorDocs = localRerank(vectorDocs, queries, finalTopN, semanticThreshold);
        }
        if (vectorDocs.isEmpty()) {
            log.debug("精排后无相关文档，前端不再返回引用");
            return List.of();
        }
        log.debug("精排后保留 {} 个文档（Reranker={}）", vectorDocs.size(), rerankerService != null);
        // 租户自定义优先：指定租户（非0）的文档排在前，超管默认文档（租户0）排在后
        if (!request.getTenantCode().equals("0")) {
            vectorDocs = vectorDocs.stream().sorted(Comparator.comparingInt(d -> {
                Object t = d.getMetadata().get("tenantCode");
                return t != null && request.getTenantCode().equals(String.valueOf(t)) ? 0 : 1;
            })).toList();
        }
        log.debug("向量检索找到 {} 个相关文档（查询词{}个，租户{}优先）", vectorDocs.size(), queries.size(), request.getTenantCode());
        return vectorDocs;
    }

    /**
     * 步骤3：从精排后的文档提取知识库来源（供前端展示和点击查看原文）。
     */
    private List<Map<String, Object>> extractSources(List<Document> vectorDocs) {
        return vectorDocs.stream().map(doc -> {
            Map<String, Object> src = new LinkedHashMap<>();
            Map<String, Object> meta = doc.getMetadata();
            src.put("fileName", meta.getOrDefault("source", "未知文件"));
            src.put("title", meta.getOrDefault("title", "未命名文档"));
            src.put("docCode", meta.get("docCode"));
            src.put("tenantCode", meta.get("tenantCode"));
            src.put("chunkIndex", meta.get("chunkIndex"));
            src.put("pageNumber", meta.getOrDefault("pageNumber", 1));
            src.put("titleHierarchy", meta.get("titleHierarchy"));
            src.put("content", doc.getText());
            src.put("deleted", false);
            return src;
        }).toList();
    }

    /**
     * 步骤4：联动父级粗粒度切片，补全推理上下文（多跳 RAG 核心）。
     */
    private String buildCoarseContext(List<Document> vectorDocs) {
        Set<String> parentIds = vectorDocs.stream()
                .map(doc -> doc.getMetadata().get("parentChunkId"))
                .filter(Objects::nonNull).map(Object::toString)
                .filter(id -> !id.isBlank()).collect(Collectors.toSet());
        if (parentIds.isEmpty()) {
            return "";
        }
        List<DocumentChunk> parentChunks = new ArrayList<>(chunkRepository.findAllById(parentIds));
        if (parentChunks.isEmpty()) {
            return "";
        }
        log.debug("联动 {} 个父级粗粒度切片补全上下文", parentChunks.size());
        return parentChunks.stream().map(DocumentChunk::getContent)
                .filter(Objects::nonNull).distinct().collect(Collectors.joining("\n---\n"));
    }

    /**
     * 步骤5：多跳图谱召回（限制数量，避免 prompt 过大触发限流/超时）。
     */
    private List<GraphRagResult> graphRecall(List<Document> vectorDocs, GraphRagRequest request) {
        List<String> chunkIds = vectorDocs.stream().map(Document::getId).toList();
        List<GraphRagResult> multiHopResults =
                this.chunkRepository.multiHopRetrieve(chunkIds, request.getTenantCode(), request.getSystemType(), 30);
        log.debug("多跳图谱召回找到 {} 个关联文档块", multiHopResults.size());
        return multiHopResults;
    }

    /**
     * 步骤6：拼接上下文（细粒度检索 + 粗粒度推理补全 + 图谱多跳），限制总长度避免 token 超限。
     */
    private ContextResult assembleContextFinal(List<Document> vectorDocs, List<Map<String, Object>> sources,
                                               String coarseContext, List<GraphRagResult> multiHopResults,
                                               String historyContext) {
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
        return new ContextResult(fullContext.toString(), sources, historyContext);
    }

    /**
     * 将 sources 序列化为 JSON 数组字符串，作为 type=sources 事件的 text 载荷。
     */
    private String toJsonArray(List<Map<String, Object>> sources) {
        try {
            return ContextUtil.OBJECT_MAPPER.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("序列化 sources 失败: {}", e.getMessage());
            return "[]";
        }
    }

    private @NonNull String getFilterExpr(GraphRagRequest request) {
        String tenantCode = request.getTenantCode();
        String systemType = request.getSystemType();
        // 指定租户 + 默认租户 + 指定系统 全部文档
        return """
                enabled == true && status == 'SUCCESS' && (tenantCode == '%s'
                 || tenantCode == '0') && systemType == '%s'
                """.formatted(tenantCode, systemType);
    }

    /**
     * 获取知识图谱数据（用于前端可视化）
     * 返回 DocumentChunk 节点、KnowledgeEntity 节点及 MENTIONS / RELATE_TO 关系
     *
     * @param tenantCode 租户编码（可选，为空则不过滤）
     * @param systemType 系统类型（可选，为空则不过滤）
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

            log.debug("图谱数据查询完成: 节点={}, 边={}", nodes.size(), edges.size());
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
        log.debug("开始处理文档入库: fileName={}, docCode={}", request.getFileName(), request.getDocCode());

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
                    log.debug("图谱构建完成: docCode={}", request.getDocCode());
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
            log.debug("文档已提交异步处理: documentId={}, fileName={}, status=PROCESSING",
                    response.getDocumentId(), response.getFileName());
            return Mono.just(response);
        });
    }

    /**
     * Reprocess a stuck PROCESSING doc: clean vectors+graph only (keep source file and
     * document_info row), then re-run conversion/chunk/vector/graph from the on-disk file.
     */
    public Mono<DocumentResponse> reprocessKnowledgeDoc(DocumentUploadRequest request, String documentId) {
        String docCode = request.getDocCode();
        return this.documentService.cleanVectorsAndGraphOnly(docCode).then(Mono.fromCallable(() -> {
            DocumentService.UploadContext context = new DocumentService.UploadContext();
            context.setDocumentId(documentId);
            DocumentResponse resp = DocumentResponse.builder()
                    .documentId(com.motcs.commons.utils.FileUtils.parseDocumentId(documentId)).docCode(docCode)
                    .tenantCode(request.getTenantCode()).systemType(request.getSystemType())
                    .fileName(request.getFileName()).status("PROCESSING")
                    .title(request.getTitle()).description(request.getDescription())
                    .userId(request.getUserId()).build();
            context.setResponse(resp);
            return context;
        })).flatMap(context -> {
            this.documentService.processDocumentAsync(request, context)
                    .then(Mono.fromRunnable(() -> {
                        buildKnowledgeGraph(docCode);
                        this.documentService.markDocumentSuccess(documentId);
                    }))
                    .onErrorResume(e -> {
                        log.error("reprocess document failed: {}", e.getMessage(), e);
                        this.documentService.failUpload(context, e.getMessage());
                        return Mono.empty();
                    }).subscribeOn(Schedulers.boundedElastic()).subscribe();
            return Mono.just(context.getResponse());
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
            log.debug("docCode={} 分片数 {} 超过上限 {}, 仅对前 {} 片做实体抽取",
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

        log.debug("开始为 docCode={} 构建图谱：细粒度分片 {} 个，分 {} 批（每批 {} 片，并发 2）",
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

        log.debug("图谱构建完成: docCode={}, 细粒度分片={}, 批次数={}", docCode, fineChunks.size(), batches.size());
    }

    /**
     * 批量处理：将多个分片 内容合并后一次 AI 调用抽取实体，结果应用到批次内所有分片
     */
    private void processBatchEntities(List<DocumentChunk> batch) {
        // 合并批次内所有分片 内容，用分隔符区分
        String combined = batch.stream().map(DocumentChunk::getContent)
                .filter(Objects::nonNull).collect(Collectors.joining("\n---\n"));
        if (combined.isBlank()) return;

        EntityExtractionResult extraction = extractEntities(combined);
        if (ObjectUtils.isEmpty(extraction) || ObjectUtils.isEmpty(extraction.entities())) {
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
            if (ObjectUtils.isEmpty(e.name())) continue;
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
                this.neo4jClient.query(cypher).bind(chunk.getId())
                        .to("chunkId").bind(entity.getName())
                        .to("entityName").fetch().one();
            } catch (Exception ex) {
                log.warn("建立 MENTIONS 关系失败: chunkId={}, entity={}, {}",
                        chunk.getId(), entity.getName(), ex.getMessage());
            }
        }

        // 3. 建立实体之间的 RELATE_TO 关系（用 MERGE 避免并行覆盖）
        if (!ObjectUtils.isEmpty(extraction.relations())) {
            for (ExtractedRelation rel : extraction.relations()) {
                if (ObjectUtils.isEmpty(rel.source()) || ObjectUtils.isEmpty(rel.target())) continue;
                createRelateToRelation(rel.source().trim(), rel.target().trim(),
                        StringUtils.hasLength(rel.type()) ? rel.type().trim() : "关联");
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
        if (ObjectUtils.isEmpty(text)) {
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
    private Mono<String> buildHistoryContext(String sessionId) {
        Mono<String> just = Mono.just("（无历史对话）");
        if (ObjectUtils.isEmpty(sessionId)) return just;
        // 1. 准确统计会话总条数
        return this.chatMessageRepository.countBySessionId(sessionId).flatMap(count -> {
            int totalCount = count.intValue();
            if (totalCount == 0) {
                return just;
            }
            Mono<List<ChatMessage>> listMono = this.chatMessageRepository
                    .findRecentBySessionId(sessionId, HISTORY_KEEP_RECENT, 0).collectList();
            return listMono.flatMap(recent -> {
                if (ObjectUtils.isEmpty(recent)) {
                    return just;
                }
                Collections.reverse(recent);
                String recentText = recent.stream()
                        .map(h -> "用户：%s\n助手：%s".formatted(h.getQuestion(),
                                (StringUtils.hasLength(h.getAnswer()) ? h.getAnswer() : "")))
                        .collect(Collectors.joining("\n\n"));

                // 不足5条，直接返回原始对话
                if (totalCount <= HISTORY_KEEP_RECENT) {
                    log.debug("加载多轮历史 {} 条（未触发摘要）", totalCount);
                    return Mono.just(recentText);
                }
                Mono<ChatSessionSummary> summaryMono = this.summaryRepository.findBySessionId(sessionId)
                        .defaultIfEmpty(new ChatSessionSummary());
                return summaryMono.flatMap(summaryRecord -> {
                    int lastCount = !ObjectUtils.isEmpty(summaryRecord.getLastMessageCount())
                            ? summaryRecord.getLastMessageCount() : 0;
                    // 每新增5条触发一次重新摘要（第5、10、15...条时）
                    boolean needRecordSummary = ObjectUtils.isEmpty(summaryRecord.getSummary())
                            || totalCount - lastCount >= HISTORY_KEEP_RECENT;

                    String summary;
                    if (needRecordSummary) {
                        // 本次不阻塞，异步生成摘要，下次提问自动使用
                        int olderCount = totalCount - HISTORY_KEEP_RECENT;
                        String oldSummary = ObjectUtils.isEmpty(summaryRecord.getSummary()) ? summaryRecord.getSummary() : "";
                        Mono.fromRunnable(() -> {
                            // 加载老对话（正序，取最早的 olderCount 条，即除最近5条外的全部）
                            List<ChatMessage> older = this.chatMessageRepository
                                    .findBySessionId(sessionId, olderCount).collectList().block();
                            if (ObjectUtils.isEmpty(older)) return;
                            String olderText = older.stream()
                                    .map(h -> "用户：%s\n助手：%s".formatted(h.getQuestion(),
                                            (StringUtils.hasLength(h.getAnswer()) ? h.getAnswer() : "")))
                                    .collect(Collectors.joining("\n\n"));
                            if (StringUtils.hasLength(oldSummary)) {
                                olderText = "【此前摘要】\n%s\n\n【新增对话】\n%s".formatted(oldSummary, olderText);
                            }
                            String newSummary = summarizeHistory(olderText);
                            saveSummary(sessionId, newSummary, totalCount);
                            log.debug("异步生成会话摘要完成: sessionId={}, 老对话{}条, 总{}条", sessionId, olderCount, totalCount);
                        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
                        // 本次使用旧摘要（没有则只用最近5条原始对话）
                        summary = oldSummary;
                        log.debug("会话摘要待更新，本次使用旧摘要: sessionId={}, 总{}条", sessionId, totalCount);
                    } else {
                        summary = summaryRecord.getSummary();
                        log.debug("复用会话摘要: sessionId={}, 总{}条", sessionId, totalCount);
                    }

                    if (summary.isBlank()) return Mono.just(recentText);
                    return Mono.just("【历史对话摘要】\n" + summary + "\n\n【最近对话】\n" + recentText);
                }).switchIfEmpty(just);
            });
        });
    }

    /**
     * 调用AI生成对话摘要
     */
    private String summarizeHistory(String historyText) {
        try {
            return chatClient.prompt().system("你是对话摘要助手，只输出摘要内容，不要任何解释、标题或Markdown格式。")
                    .user("请简要总结以下对话历史的核心内容，保留关键事实、用户需求和助手结论，不超过300字：\n\n" + historyText)
                    .call().content();
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
            if (ObjectUtils.isEmpty(existing)) {
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
    public Mono<Void> saveConversation(GraphRagRequest request) {
        if (ObjectUtils.isEmpty(request.getQuestion())) {
            return Mono.empty();
        }
        if (ObjectUtils.isEmpty(request.getAnswer())) {
            request.setAnswer("");
        }
        // 查询该会话已有的标题（取最新一条记录的title），新记录继承相同标题
        Mono<String> stringMono = this.chatMessageRepository
                .findRecentBySessionId(request.getSessionId(), 1, 0)
                .next().map(ChatMessage::getTitle).defaultIfEmpty("");
        return stringMono.flatMap(existingTitle -> {
            ChatMessage record = ChatMessage.builder().userId(request.getUserId())
                    .sessionId(request.getSessionId()).title(existingTitle)
                    .question(request.getQuestion()).answer(request.getAnswer())
                    .reasoning(request.getReasoning()).sources(request.getSources())
                    .tenantCode(request.getTenantCode()).systemType(request.getSystemType())
                    .apiKeyId(request.getApiKeyId()).createTime(LocalDateTime.now()).build();
            return this.chatMessageRepository.save(record).publishOn(Schedulers.boundedElastic()).doOnSuccess(r -> {
                if (!ObjectUtils.isEmpty(r)) {
                    log.debug("对话记录已保存: id={}, userId={}, sessionId={}, question={}", r.getId(), request.getUserId(), request.getSessionId(),
                            request.getQuestion().length() > 50 ? request.getQuestion().substring(0, 50) + "..." : request.getQuestion());
                    // 同步更新 chat_session 主表
                    upsertChatSession(request, existingTitle).subscribe();
                    // 首次问答完成且尚无标题时，异步生成会话主题
                    if (!StringUtils.hasLength(existingTitle)) {
                        generateSessionTitleAsync(request);
                    }
                }
            });
        }).then();
    }

    /**
     * 新增或更新 chat_session 主表记录
     */
    private Mono<Void> upsertChatSession(GraphRagRequest request, String title) {
        return this.chatSessionRepository.findBySessionId(request.getSessionId())
                .switchIfEmpty(Mono.defer(() -> {
                    ChatSession session = ChatSession.builder().sessionId(request.getSessionId())
                            .title(title).userId(request.getUserId()).apiKeyId(request.getApiKeyId())
                            .tenantCode(request.getTenantCode()).systemType(request.getSystemType())
                            .createTime(LocalDateTime.now()).updateTime(LocalDateTime.now()).build();
                    return Mono.just(session);
                })).flatMap(existing -> {
                    existing.setTitle(title);
                    existing.setUpdateTime(LocalDateTime.now());
                    return this.chatSessionRepository.save(existing).then();
                });
    }

    /**
     * 异步生成会话主题（首次问答后调用，生成后更新该会话所有记录的title，固定不变）
     */
    private void generateSessionTitleAsync(GraphRagRequest request) {
        Mono.fromRunnable(() -> {
            try {
                log.debug("开始生成对话的主题！");
                // 计数检查（异步线程内block安全）：第一次问答完成后即生成标题
                Long count = this.chatMessageRepository.countBySessionId(request.getSessionId()).blockOptional().orElse(0L);
                if (count > 1) {
                    return;
                }
                // 再次确认尚无标题
                ChatMessage latest = this.chatMessageRepository.findRecentBySessionId(request.getSessionId(), 1, 0).blockFirst();
                if (!ObjectUtils.isEmpty(latest) && !ObjectUtils.isEmpty(latest.getTitle())) {
                    return;
                }
                String title = this.chatClient.prompt()
                        .system("你是对话主题生成助手，只返回主题名称，不要任何解释、引号或标点，不超过15个字。")
                        .user("请根据以下对话生成一个简洁的主题名称：\n用户：%s\n助手：%s".formatted(request.getQuestion(), request.getAnswer()))
                        .call().content();
                if (StringUtils.hasLength(title)) {
                    title = title.trim().replaceAll("[\"'`]", "")
                            .replaceAll("\\s+", " ");
                    if (title.length() > 30) title = title.substring(0, 30);
                    this.chatMessageRepository.updateTitle(title, request.getSessionId()).subscribe();
                    this.chatSessionRepository.updateTitle(title, request.getSessionId()).subscribe();
                    this.chatSessionUsageRepository.updateTitle(request.getSessionId(), title).subscribe();
                    log.debug("会话主题已生成并更新: sessionId={}, title={}", request.getSessionId(), title);
                }
            } catch (Exception e) {
                log.warn("生成会话主题失败: sessionId={}, {}", request.getSessionId(), e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 按用户查询最近对话记录
     */
    public Mono<List<ChatMessage>> getConversations(String userId, String tenantCode, String systemType) {
        return this.chatMessageRepository.findByUser(userId, tenantCode, systemType).collectList();
    }

    /**
     * 按会话ID分页查询对话
     * order=desc（默认，倒序）；order=asc（正序，导出用）
     */
    public Mono<List<ChatMessage>> getConversationsBySession(String sessionId, Pageable pageable) {
        String sql = "SELECT * FROM chat_message WHERE session_id = :sessionId" + ContextUtil.applyPage(pageable);
        return super.queryWith(sql, Map.of("sessionId", sessionId), ChatMessage.class).collectList();
    }

    // ==================== 对话历史摘要压缩 ====================

    /**
     * 查询用户的会话列表（取每个会话最后一条记录的title作为标题，按最后活跃时间倒序）
     */
    public Mono<Page<ChatSession>> getSessions(ChatSessionRequest request, Pageable pageable) {
        String query = request.whereSql(pageable);
        Map<String, Object> params = request.getParams();
        Flux<ChatSession> listMono = super.queryWith(query, params, ChatSession.class);
        Mono<Long> countMono = super.countWith(request.countSql(), params);
        return Mono.zip(listMono.collectList(), countMono).map(tuple2 ->
                new PageImpl<>(tuple2.getT1(), pageable, tuple2.getT2()));
    }

    /**
     * 判断 chat_session 主表是否有数据
     */
    public Mono<Boolean> hasAnySession() {
        return this.chatSessionRepository.count().map(count -> count > 0);
    }

    /**
     * 同步老的聊天记录到 chat_session 主表
     * 从 chat_message 按 session_id 分组，取每个会话最新一条记录的元信息
     * 已存在的会话跳过，不重复迁移
     */
    public Mono<Long> syncChatSessions() {
        return this.chatMessageRepository.findAllSessionGroups()
                .filterWhen(msg -> this.chatSessionRepository
                        .findBySessionId(msg.getSessionId()).hasElement().map(e -> !e))
                .map(msg -> ChatSession.builder().sessionId(msg.getSessionId())
                        .title(msg.getTitle()).userId(msg.getUserId()).apiKeyId(msg.getApiKeyId())
                        .tenantCode(msg.getTenantCode()).systemType(msg.getSystemType())
                        .createTime(msg.getCreateTime()).updateTime(msg.getCreateTime()).build())
                .flatMap(this.chatSessionRepository::save).count();
    }

    /**
     * 按会话ID + API Key 查询对话消息（校验归属：仅该 Key 创建的消息）
     */
    public Mono<List<ChatMessage>> querySession(String sessionId, Long apiKeyId) {
        return this.chatMessageRepository.querySession(sessionId, apiKeyId).collectList();
    }

    // ==================== 对话记录 ====================

    /**
     * 删除单个会话（仅当该会话存在属于该 Key 的消息才删除；删除后若会话无剩余消息，同步清理摘要）
     *
     * @return true=已删除；false=该会话不属于此 API Key，未做任何改动
     */
    public Mono<Boolean> deleteSessionByApiKey(String sessionId, Long apiKeyId) {
        return this.chatMessageRepository.countSession(sessionId, apiKeyId).flatMap(count -> {
            if (count == 0) {
                return Mono.error(RestServerException.withMsg("当前密钥下没有这个对话的权限！"));
            }
            return this.chatMessageRepository.deleteBySessionId(sessionId)
                    .then(this.chatSessionRepository.deleteBySessionId(sessionId))
                    .then(summaryRepository.deleteBySessionId(sessionId))
                    .thenReturn(true);
        });
    }

    /**
     * 批量删除会话（仅删除属于该 Key 的会话，返回实际删除数）
     */
    public Mono<Long> deleteSessions(List<String> sessionIds, Long apiKeyId) {
        if (ObjectUtils.isEmpty(sessionIds)) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(sessionIds)
                .flatMap(id -> this.deleteSessionByApiKey(id, apiKeyId))
                .filter(Boolean::booleanValue).count();
    }

    /**
     * 更新会话标题（批量更新该会话所有记录的title）
     */
    public Mono<Integer> updateSessionTitle(String sessionId, String title) {
        if (ObjectUtils.isEmpty(title)) {
            return Mono.just(0);
        }
        return this.chatMessageRepository.updateTitle(title.trim(), sessionId)
                .then(this.chatSessionRepository.updateTitle(title.trim(), sessionId))
                .flatMap(cnt -> this.chatSessionUsageRepository.updateTitle(sessionId, title.trim()).thenReturn(cnt))
                .doOnSuccess(cnt -> log.debug("会话标题已更新: sessionId={}, title={}, 影响行数={}", sessionId, title, cnt));
    }

    /**
     * 删除单个会话（该会话下所有对话记录 + 摘要）
     */
    public Mono<Void> deleteSession(String sessionId) {
        return this.chatMessageRepository.deleteBySessionId(sessionId)
                .then(this.summaryRepository.deleteBySessionId(sessionId))
                .then(this.chatSessionRepository.deleteBySessionId(sessionId))
                .doOnSuccess(_ -> log.debug("已删除会话及摘要: sessionId={}", sessionId));
    }

    /**
     * 批量删除多个会话（对话记录 + 摘要）
     */
    public Mono<Void> deleteSessions(List<String> sessionIds) {
        if (ObjectUtils.isEmpty(sessionIds)) return Mono.empty();
        return Flux.fromIterable(sessionIds).flatMap(id -> this.chatMessageRepository.deleteBySessionId(id)
                        .then(this.summaryRepository.deleteBySessionId(id))
                        .then(this.chatSessionRepository.deleteBySessionId(id)))
                .then().doOnSuccess(_ -> log.debug("已批量删除 {} 个会话及摘要", sessionIds.size()));
    }

    /**
     * 检索上下文 + 改写展示词 内部封装（供两个分支共用）
     */
    private record Enriched(ContextResult ctx, String historyContext, String rewriteDisplay,
                            List<ChatStreamEvent> stageEvents) {
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
     * 记录一次平台对话的 token 用量到 chat_usage_record。
     * input=prompt，output=completion（含推理），reasoning=推理 token（从 nativeUsage 解析），
     * cache=缓存命中 token。解析失败的字段按 0 计。
     */
    public Mono<Void> recordChatUsage(String userId, String sessionId, String model, String title, Usage usage) {
        if (usage == null) {
            return Mono.empty();
        }
        int input = usage.getPromptTokens();
        int output = usage.getCompletionTokens();
        int reasoning = 0; // 思考token暂不统计
        int cache = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens().intValue();
        int total = input + output;
        var price = com.motcs.core.auth.keys.usage.quota.ModelPricing.of(model);
        // 百度千帆口径：未命中缓存的输入按输入价计，命中缓存的输入按缓存价计
        int uncachedInput = Math.max(input - cache, 0);
        double inputCost = uncachedInput / 1000.0 * price.inPerK() + cache / 1000.0 * price.cachePerK();
        double outputCost = output / 1000.0 * price.outPerK();
        double cacheCost = cache / 1000.0 * price.cachePerK();
        double totalCost = inputCost + outputCost + cacheCost;
        ChatUsageRecord record = ChatUsageRecord.builder()
                .userId(userId).sessionId(sessionId).model(model)
                .inputTokens(input).outputTokens(output)
                .reasoningTokens(reasoning).cacheTokens(cache)
                .createdTime(LocalDateTime.now()).build();
        Mono<Void> detail = this.chatUsageRecordRepository.save(record).then();
        Mono<Void> session = this.chatSessionUsageRepository.accumulate(sessionId, userId, title,
                input, output, reasoning, cache, total, inputCost, outputCost, cacheCost, totalCost).then();
        return Mono.when(detail, session)
                .onErrorResume(e -> {
                    log.warn("记录对话用量失败: {}", e.getMessage());
                    return Mono.empty();
                });
    }

    public record QueryResult(List<Map<String, Object>> sources, Flux<ChatStreamEvent> answer,
                              AtomicReference<Usage> usageRef, String rewriteQuery) {
    }

    /**
     * 内部上下文封装
     */
    private record ContextResult(String fullContext, List<Map<String, Object>> sources, String historyContext) {
    }

}
