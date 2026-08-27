package com.motcs.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.motcs.commons.Utils;
import com.motcs.dto.*;
import com.motcs.knowledge.graph.GraphRagKnowledgeService;
import com.motcs.knowledge.graph.GraphRagQuery;
import com.motcs.knowledge.record.ChatMessage;
import com.motcs.service.DocumentService;
import com.motcs.util.ByteArrayMultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器（上传、查询、删除、智能问答、对话历史、知识图谱）
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final WebClient.Builder webClientBuilder;
    private final GraphRagKnowledgeService graphRagKnowledgeService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 上传文档接口（WebFlux 响应式，使用 FilePart）
     * POST /api/documents/upload
     * Content-Type: multipart/form-data
     */
    @PostMapping("/upload")
    public Mono<ResponseEntity<DocumentResponse>> uploadDocument(
            @RequestPart("file") FilePart file,
            @RequestPart(value = "title", required = false) String title,
            @RequestPart(value = "description", required = false) String description,
            @RequestPart(value = "docCode", required = false) String docCode,
            @RequestPart(value = "tenantCode", required = false) String tenantCode,
            @RequestPart(value = "systemType", required = false) String systemType,
            @RequestPart(value = "userId", required = false) String userId) {

        String finalTenant = (tenantCode == null || tenantCode.isBlank()) ? "default" : tenantCode;
        String finalSystem = (systemType == null || systemType.isBlank()) ? "default" : systemType;

        log.info("收到文档上传请求: fileName={}, docCode={}, tenantCode={}, systemType={}, userId={}",
                file.filename(), docCode, finalTenant, finalSystem, userId);

        return file.content().collectList().map(buffers -> {
            byte[] fileBytes = Utils.concatenateBuffers(buffers);
            MediaType contentType1 = file.headers().getContentType();
            String contentType = contentType1 != null ? contentType1.toString() : "application/octet-stream";
            return new ByteArrayMultipartFile("file", file.filename(), contentType, fileBytes);
        }).flatMap(multipartFile -> buildAndUpload(multipartFile,
                title, description, docCode, finalTenant, finalSystem, userId));
    }

    /**
     * 通过远程 URL 上传文档接口（WebClient 非阻塞下载）
     * POST /api/documents/upload-by-url
     */
    @PostMapping("/upload-by-url")
    public Mono<ResponseEntity<DocumentResponse>> uploadByUrl(@RequestBody FileUploadRequest uploadRequest) {

        log.info("收到URL上传请求: url={}, docCode={}, tenantCode={}, systemType={}",
                uploadRequest.getUrl(), uploadRequest.getDocCode(), uploadRequest.getTenantCode(), uploadRequest.getSystemType());

        String fileName = Utils.extractFileNameFromUrl(uploadRequest.getUrl());
        String contentType = Utils.filesProbeContentType(fileName);

        return webClientBuilder.build().get().uri(uploadRequest.getUrl())
                .retrieve().bodyToMono(byte[].class).flatMap(fileBytes -> {
                    log.info("远程文件下载成功: fileName={}, size={}", fileName, fileBytes.length);
                    MultipartFile multipartFile = new ByteArrayMultipartFile("file", fileName, contentType, fileBytes);
                    return buildAndUpload(multipartFile,
                            uploadRequest.getTitle(), uploadRequest.getDescription(),
                            uploadRequest.getDocCode(), uploadRequest.getTenantCode(),
                            uploadRequest.getSystemType(), uploadRequest.getUserId());
                }).onErrorResume(e -> {
                    log.error("URL上传失败: url={}, error={}", uploadRequest.getUrl(), e.getMessage(), e);
                    DocumentResponse err = DocumentResponse.builder().status("FAILED")
                            .errorMessage("远程文件下载或处理失败: " + e.getMessage()).build();
                    return Mono.just(ResponseEntity.badRequest().body(err));
                });
    }

    /**
     * 构建 DocumentUploadRequest 并调用响应式入库，统一返回 ResponseEntity
     */
    private Mono<ResponseEntity<DocumentResponse>> buildAndUpload(
            MultipartFile file, String title, String description,
            String docCode, String tenantCode, String systemType, String userId) {

        DocumentUploadRequest request = DocumentUploadRequest.builder().file(file)
                .fileName(file.getOriginalFilename()).description(description)
                .title(title != null ? title : file.getOriginalFilename())
                .documentType(Utils.getFileType(file.getOriginalFilename()))
                .docCode(docCode).tenantCode(tenantCode).systemType(systemType)
                .userId(userId).build();

        return graphRagKnowledgeService.insertKnowledgeDoc(request).map(response -> {
            if ("FAILED".equals(response.getStatus())) {
                log.warn("文档上传失败: {}", response.getErrorMessage());
                return ResponseEntity.badRequest().body(response);
            }
            log.info("文档已提交处理: documentId={}, docCode={}, fileName={}, status={}",
                    response.getDocumentId(), response.getDocCode(),
                    response.getFileName(), response.getStatus());
            return ResponseEntity.ok(response);
        });
    }

    /**
     * GraphRAG 知识问答接口（SSE 流式返回，支持多轮对话 + 知识库来源）
     * POST /api/documents/query
     * SSE 事件：先发送 event:sources（引用的知识库片段），再逐 token 发送回答
     * 回答完成后自动保存对话记录（含 userId、sessionId、sources）
     */
    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> graphRagQuery(GraphRagQuery ragQuery) {
        // 未传 sessionId 时自动生成，保证多轮可用
        if (ragQuery.getSessionId() == null || ragQuery.getSessionId().isBlank()) {
            ragQuery.setSessionId(java.util.UUID.randomUUID().toString());
        }
        final String sessionId = ragQuery.getSessionId();
        StringBuilder answerBuilder = new StringBuilder();
        final String[] sourcesJson = {"[]"};

        return graphRagKnowledgeService.graphRagQueryStream(ragQuery).flatMapMany(result -> {
            // 序列化来源
            try {
                sourcesJson[0] = objectMapper.writeValueAsString(result.sources());
            } catch (Exception e) {
                sourcesJson[0] = "[]";
            }
            // 发送顺序：1.sessionId 2.sources 3.回答token（其中思考token带__REASONING__:前缀，仅前端展示不入库）
            return Flux.concat(
                    Mono.just("__SESSION__:" + sessionId),
                    Mono.just("__SOURCES__:" + sourcesJson[0]),
                    result.answer().doOnNext(seg -> {
                        if (!seg.startsWith("__REASONING__:")) answerBuilder.append(seg);
                    })
            );
        }).publishOn(Schedulers.boundedElastic()).doFinally(signal -> {
            // 取消时由前端手动保存（避免重复），正常完成/出错时保存（answer 可为空，确保提问不丢失）
            if (signal == reactor.core.publisher.SignalType.CANCEL) return;
            if (ragQuery.getQuestion() != null && !ragQuery.getQuestion().isBlank()) {
                graphRagKnowledgeService.saveConversation(ragQuery.getQuestion(), answerBuilder.toString(),
                                ragQuery.getUserId(), sessionId, sourcesJson[0], ragQuery.getTenantCode(), ragQuery.getSystemType())
                        .subscribe();
            }
        }).doOnError(e -> log.error("问答SSE流出错: {}", e.getMessage(), e));
    }

    /**
     * 手动保存对话记录（前端中断回答时调用，确保部分回答入库）
     * POST /api/documents/conversations
     */
    @PostMapping("/conversations")
    public Mono<ResponseEntity<Map<String, Object>>> saveConversation(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String answer = body.get("answer");
        String userId = body.get("userId");
        String sessionId = body.get("sessionId");
        String sources = body.get("sources");
        String tenantCode = body.get("tenantCode");
        String systemType = body.get("systemType");
        return graphRagKnowledgeService.saveConversation(question, answer,
                        userId, sessionId, sources, tenantCode, systemType)
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    return ResponseEntity.ok(result);
                }));
    }

    /**
     * 对话记录查询接口（按用户）
     * GET /api/documents/conversations
     */
    @GetMapping("/conversations")
    public Mono<ResponseEntity<List<ChatMessage>>> getConversations(
            @RequestParam("userId") String userId,
            @RequestParam(value = "tenantCode", required = false) String tenantCode,
            @RequestParam(value = "systemType", required = false) String systemType,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        log.info("收到对话记录查询: userId={}, tenant={}, system={}", userId, tenantCode, systemType);
        return graphRagKnowledgeService.getConversations(userId, tenantCode, systemType, limit)
                .map(ResponseEntity::ok);
    }

    /**
     * 按会话ID分页查询对话
     * GET /api/documents/conversations/session?sessionId=xxx&limit=10&offset=0&order=desc
     * order=desc（默认，倒序，前端反转后正序展示）；order=asc（正序，导出用）
     */
    @GetMapping("/conversations/session")
    public Mono<ResponseEntity<List<ChatMessage>>> getConversationsBySession(
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "limit", defaultValue = "10") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "order", defaultValue = "desc") String order) {
        return graphRagKnowledgeService.getConversationsBySession(sessionId, limit, offset, order)
                .map(ResponseEntity::ok);
    }

    /**
     * 会话列表查询接口（按用户，去重 sessionId）
     * GET /api/documents/sessions
     */
    @GetMapping("/sessions")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getSessions(
            @RequestParam("userId") String userId,
            @RequestParam(value = "tenantCode", required = false) String tenantCode,
            @RequestParam(value = "systemType", required = false) String systemType,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return graphRagKnowledgeService.getSessions(userId, tenantCode, systemType, limit)
                .map(ResponseEntity::ok);
    }

    /**
     * 更新会话标题（批量更新该会话所有记录的title）
     * PUT /api/documents/conversations/session/{sessionId}/title
     * Body: {"title": "新标题"}
     */
    @PutMapping("/conversations/session/{sessionId}/title")
    public Mono<ResponseEntity<Map<String, Object>>> updateSessionTitle(
            @PathVariable("sessionId") String sessionId,
            @RequestBody Map<String, String> body) {
        String title = body != null ? body.get("title") : null;
        log.info("收到更新会话标题请求: sessionId={}, title={}", sessionId, title);
        return graphRagKnowledgeService.updateSessionTitle(sessionId, title)
                .map(cnt -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("sessionId", sessionId);
                    result.put("title", title);
                    result.put("updated", cnt);
                    return ResponseEntity.ok(result);
                });
    }

    /**
     * 删除单个会话（该会话下所有对话记录）
     * DELETE /api/documents/conversations/session/{sessionId}
     */
    @DeleteMapping("/conversations/session/{sessionId}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteSession(
            @PathVariable("sessionId") String sessionId) {
        log.info("收到删除会话请求: sessionId={}", sessionId);
        return graphRagKnowledgeService.deleteSession(sessionId)
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("sessionId", sessionId);
                    return ResponseEntity.ok(result);
                }));
    }

    /**
     * 批量删除会话
     * DELETE /api/documents/conversations/batch
     * Body: ["sessionId1", "sessionId2", ...]
     */
    @DeleteMapping("/conversations/batch")
    public Mono<ResponseEntity<Map<String, Object>>> deleteSessionsBatch(
            @RequestBody List<String> sessionIds) {
        log.info("收到批量删除会话请求: count={}", sessionIds != null ? sessionIds.size() : 0);
        return graphRagKnowledgeService.deleteSessions(sessionIds)
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("deletedCount", sessionIds != null ? sessionIds.size() : 0);
                    return ResponseEntity.ok(result);
                }));
    }

    /**
     * 查询文档列表接口
     * GET /api/documents/list
     */
    @GetMapping("/list")
    public Mono<ResponseEntity<List<DocumentResponse>>> listDocuments(DocumentQueryRequest request) {
        log.info("收到文档列表查询请求: tenantCode={}", request.getTenantCode());
        return documentService.queryDocuments(request.getTenantCode(), request.getSystemType())
                .doOnNext(docs -> log.info("查询到 {} 个文档", docs.size()))
                .map(ResponseEntity::ok);
    }

    /**
     * 删除文档接口（按 docCode 级联删除：分片、独占知识点、关系、向量、原始文件）
     * DELETE /api/documents/docCode/{docCode}
     */
    @DeleteMapping("/docCode/{docCode}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteDocument(
            @PathVariable("docCode") String docCode) {
        log.info("收到文档删除请求: docCode={}", docCode);
        return documentService.deleteByDocCode(docCode)
                .then(Mono.fromCallable(() -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("docCode", docCode);
                    result.put("message", "文档已删除");
                    return ResponseEntity.ok(result);
                }));
    }

    /**
     * 文档统计接口（轻量聚合，不返回明细）
     * GET /api/documents/stats?tenantCode=xxx
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<DocumentStatsResponse>> getStats(DocumentQueryRequest request) {
        return documentService.getStats(request).map(ResponseEntity::ok);
    }

    /**
     * 健康检查接口
     * GET /api/documents/health
     */
    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("Document service is running");
    }

    /**
     * 知识图谱数据接口（供前端可视化）
     * GET /api/documents/graph
     */
    @GetMapping("/graph")
    public Mono<ResponseEntity<Map<String, Object>>> getGraph(
            @RequestParam(value = "tenantCode", required = false) String tenantCode,
            @RequestParam(value = "systemType", required = false) String systemType,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        log.info("收到图谱数据请求: tenantCode={}, systemType={}, limit={}", tenantCode, systemType, limit);
        return graphRagKnowledgeService.getGraphData(tenantCode, systemType, limit)
                .map(ResponseEntity::ok);
    }

}
