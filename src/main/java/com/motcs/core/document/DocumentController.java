package com.motcs.core.document;

import com.motcs.commons.ContextUtil;
import com.motcs.commons.annotation.RestServerException;
import com.motcs.commons.utils.ByteArrayMultipartFile;
import com.motcs.commons.utils.Utils;
import com.motcs.core.knowledge.graph.GraphRagRequest;
import com.motcs.core.knowledge.graph.GraphRagService;
import com.motcs.core.knowledge.record.ChatMessage;
import com.motcs.core.request.FileUploadRequest;
import com.motcs.core.request.SessionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档管理控制器（上传、查询、删除、智能问答、对话历史、知识图谱）
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Log4j2
@RestController
@RequiredArgsConstructor
@RequestMapping("/documents/v1")
public class DocumentController {

    private final DocumentService documentService;
    private final GraphRagService graphRagService;
    private final WebClient.Builder webClientBuilder;

    /**
     * 上传文档接口（WebFlux 响应式，使用 FilePart）
     * POST /documents/v1/upload
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
        if (ObjectUtils.isEmpty(docCode)) {
            return Mono.error(RestServerException.withMsg("文档编码（docCode）不能为空"));
        }
        if (ObjectUtils.isEmpty(tenantCode)) {
            return Mono.error(RestServerException.withMsg("租户编码（tenantCode）不能为空"));
        }
        if (ObjectUtils.isEmpty(systemType)) {
            return Mono.error(RestServerException.withMsg("系统类型（systemType）不能为空"));
        }
        if (ObjectUtils.isEmpty(userId)) {
            return Mono.error(RestServerException.withMsg("用户编码（userId）不能为空"));
        }
        log.info("收到文档上传请求: fileName={}, docCode={}, tenantCode={}, systemType={}, userId={}",
                file.filename(), docCode, tenantCode, systemType, userId);

        return file.content().collectList().map(buffers -> {
            byte[] fileBytes = Utils.concatenateBuffers(buffers);
            MediaType contentType1 = file.headers().getContentType();
            String contentType = contentType1 != null ? contentType1.toString() : "application/octet-stream";
            return new ByteArrayMultipartFile("file", file.filename(), contentType, fileBytes);
        }).flatMap(multipartFile -> buildAndUpload(multipartFile,
                title, description, docCode, tenantCode, systemType, userId));
    }

    /**
     * 通过远程 URL 上传文档接口（WebClient 非阻塞下载）
     * POST /documents/v1/upload/url
     */
    @PostMapping("/upload/url")
    public Mono<ResponseEntity<DocumentResponse>> uploadByUrl(@RequestBody FileUploadRequest uploadRequest) {
        if (ObjectUtils.isEmpty(uploadRequest.getDocCode())) {
            return Mono.error(RestServerException.withMsg("文档编码（docCode）不能为空"));
        }
        if (ObjectUtils.isEmpty(uploadRequest.getTenantCode())) {
            return Mono.error(RestServerException.withMsg("租户编码（tenantCode）不能为空"));
        }
        if (ObjectUtils.isEmpty(uploadRequest.getSystemType())) {
            return Mono.error(RestServerException.withMsg("系统类型（systemType）不能为空"));
        }
        if (ObjectUtils.isEmpty(uploadRequest.getUserId())) {
            return Mono.error(RestServerException.withMsg("用户编码（userId）不能为空"));
        }
        log.info("收到URL上传请求参数:{}", uploadRequest);
        String fileName = Utils.extractFileNameFromUrl(uploadRequest.getUrl());
        String contentType = Utils.filesProbeContentType(fileName);
        Mono<byte[]> bodyToMono = webClientBuilder.build()
                .get().uri(uploadRequest.getUrl())
                .retrieve().bodyToMono(byte[].class);
        return bodyToMono.flatMap(fileBytes -> {
            log.info("远程文件下载成功: fileName={}, size={}", fileName, fileBytes.length);
            MultipartFile multipartFile = new ByteArrayMultipartFile("file", fileName, contentType, fileBytes);
            return buildAndUpload(multipartFile, uploadRequest.getTitle(),
                    uploadRequest.getDescription(), uploadRequest.getDocCode(),
                    uploadRequest.getTenantCode(), uploadRequest.getSystemType(),
                    uploadRequest.getUserId());
        }).onErrorResume(e -> {
            log.error("URL上传失败: url={}, error={}", uploadRequest.getUrl(), e.getMessage(), e);
            DocumentResponse err = DocumentResponse.builder().status("FAILED")
                    .errorMessage("远程文件下载或处理失败: " + e.getMessage()).build();
            return Mono.just(ResponseEntity.badRequest().body(err));
        });
    }

    /**
     * GraphRAG 知识问答接口（SSE 流式返回，支持多轮对话 + 知识库来源）
     * POST /documents/v1/query
     * SSE 事件（JSON，type 字段区分）：
     * - {"type":"session","sessionId":"xxx"}
     * - {"type":"sources","sources":[...]}
     * - {"type":"reasoning","text":"思考片段"}
     * - {"type":"content","text":"正文片段"}
     * 回答完成后自动保存对话记录（含 userId、sessionId、sources、reasoning）
     */
    @PostMapping(value = "/query", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> graphRagQuery(@RequestBody GraphRagRequest request) {
        if (ObjectUtils.isEmpty(request) || ObjectUtils.isEmpty(request.getQuestion())) {
            return Flux.just(Utils.jsonEvent("error", Map.of("message", "问题（question）不能为空")));
        }
        if (ObjectUtils.isEmpty(request.getUserId())) {
            return Flux.just(Utils.jsonEvent("error", Map.of("message", "用户编码（userId）不能为空")));
        }
        final String sessionId = ObjectUtils.isEmpty(request.getSessionId())
                ? UUID.randomUUID().toString() : request.getSessionId();
        StringBuilder answerBuilder = new StringBuilder(); // AI回答完整内容累积
        StringBuilder reasoningBuilder = new StringBuilder(); // AI思考过程累积（入库）
        final String[] sourcesJson = {"[]"};

        return this.graphRagService.graphRagQueryStream(request).flatMapMany(result -> {
            // 序列化来源
            try {
                sourcesJson[0] = ContextUtil.OBJECT_MAPPER.writeValueAsString(result.sources());
            } catch (Exception e) {
                sourcesJson[0] = "[]";
            }
            // 发送顺序：1.session 2.sources 3.思考/正文事件（JSON type 字段区分思考/正文，不依赖内容判空）
            Mono<String> sessionMono = Mono.just(Utils.jsonEvent("session", Map.of("sessionId", sessionId)));
            Mono<String> sourcesMono = Mono.just(Utils.jsonEvent("sources", Map.of("sources", result.sources())));
            Flux<String> answerMono = result.answer().doOnNext(ev -> {
                if ("reasoning".equals(ev.type())) {
                    reasoningBuilder.append(ev.text());
                } else if ("content".equals(ev.type())) {
                    answerBuilder.append(ev.text());
                }
            }).map(ev -> Utils.jsonEvent(ev.type(), Map.of("text", ev.text() == null ? "" : ev.text())));
            return Flux.concat(sessionMono, sourcesMono, answerMono);
        }).publishOn(Schedulers.boundedElastic()).doFinally(signal -> {
            // 取消时由前端手动保存（避免重复），正常完成/出错时保存（answer 可为空，确保提问不丢失）
            if (signal == reactor.core.publisher.SignalType.CANCEL) return;
            if (request.getQuestion() != null && !request.getQuestion().isBlank()) {
                request.setAnswer(answerBuilder.toString());
                request.setSessionId(sessionId);
                request.setSources(sourcesJson[0]);
                request.setReasoning(reasoningBuilder.toString());
                this.graphRagService.saveConversation(request, 0L).subscribe();
            }
        }).doOnError(e -> log.error("问答SSE流出错: {}", e.getMessage(), e));
    }

    /**
     * 手动保存对话记录（前端中断回答时调用，确保部分回答入库）
     * POST /documents/v1/conversations
     */
    @PostMapping("/conversations")
    public Mono<ResponseEntity<Map<String, Object>>> saveConversation(@RequestBody GraphRagRequest request) {
        return graphRagService.saveConversation(request, 0L)
                .then(Mono.fromCallable(() -> ResponseEntity.ok(Map.of("success", true))));
    }

    /**
     * 对话记录查询接口（按用户）
     * GET /documents/v1/conversations
     */
    @GetMapping("/conversations")
    public Mono<ResponseEntity<List<ChatMessage>>> getConversations(
            @RequestParam("userId") String userId,
            @RequestParam(value = "tenantCode", required = false) String tenantCode,
            @RequestParam(value = "systemType", required = false) String systemType,
            Pageable pageable) {
        log.info("收到对话记录查询: userId={}, tenant={}, system={}, page={}, size={}",
                userId, tenantCode, systemType, pageable.getPageNumber(), pageable.getPageSize());
        return graphRagService.getConversations(userId, tenantCode, systemType, pageable.getPageSize())
                .map(ResponseEntity::ok);
    }

    /**
     * 按会话ID分页查询对话
     * GET /documents/v1/conversations/session?sessionId=xxx&limit=10&offset=0&order=desc
     * order=desc（默认，倒序，前端反转后正序展示）；order=asc（正序，导出用）
     */
    @GetMapping("/conversations/session")
    public Mono<ResponseEntity<List<ChatMessage>>> getConversationsBySession(
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "order", defaultValue = "desc") String order, Pageable pageable) {
        int limit = pageable.getPageSize();
        int offset = (int) pageable.getOffset();
        return graphRagService.getConversationsBySession(sessionId, limit, offset, order)
                .map(ResponseEntity::ok);
    }

    /**
     * 会话列表查询接口（按用户，去重 sessionId）
     * GET /documents/v1/sessions
     */
    @GetMapping("/sessions")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getSessions(
            @RequestParam("userId") String userId,
            @RequestParam(value = "tenantCode", required = false) String tenantCode,
            @RequestParam(value = "systemType", required = false) String systemType, Pageable pageable) {
        return graphRagService.getSessions(userId, tenantCode, systemType, pageable.getPageSize())
                .map(ResponseEntity::ok);
    }

    /**
     * 更新会话标题（批量更新该会话所有记录的title）
     * PUT /documents/v1/conversations/session/{sessionId}/title
     * Body: {"title": "新标题"}
     */
    @PutMapping("/conversations/session/{sessionId}/title")
    public Mono<ResponseEntity<Map<String, Object>>> updateSessionTitle(
            @PathVariable("sessionId") String sessionId, @RequestBody SessionRequest request) {
        String title = request != null ? request.getTitle() : null;
        log.info("收到更新会话标题请求: sessionId={}, title={}", sessionId, title);
        return graphRagService.updateSessionTitle(sessionId, title).map(cnt -> {
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
     * DELETE /documents/v1/conversations/session/{sessionId}
     */
    @DeleteMapping("/conversations/session/{sessionId}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteSession(@PathVariable("sessionId") String sessionId) {
        log.info("收到删除会话请求: sessionId={}", sessionId);
        return graphRagService.deleteSession(sessionId).then(Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("sessionId", sessionId);
            return ResponseEntity.ok(result);
        }));
    }

    /**
     * 批量删除会话
     * DELETE /documents/v1/conversations/batch
     * Body: ["sessionId1", "sessionId2", ...]
     */
    @DeleteMapping("/conversations/batch")
    public Mono<ResponseEntity<Map<String, Object>>> deleteSessionsBatch(@RequestBody SessionRequest request) {
        List<String> sessionIds = request != null ? request.getSessionIds() : null;
        log.info("收到批量删除会话请求: count={}", sessionIds != null ? sessionIds.size() : 0);
        return graphRagService.deleteSessions(sessionIds).then(Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("deletedCount", sessionIds != null ? sessionIds.size() : 0);
            return ResponseEntity.ok(result);
        }));
    }

    /**
     * 查询文档列表接口
     * GET /documents/v1/list
     */
    @GetMapping("/list")
    public Mono<ResponseEntity<List<DocumentResponse>>> listDocuments(DocumentRequest request) {
        log.info("收到文档列表查询请求: tenantCode={}", request.getTenantCode());
        return documentService.queryDocuments(request.getTenantCode(), request.getSystemType())
                .doOnNext(docs -> log.info("查询到 {} 个文档", docs.size()))
                .map(ResponseEntity::ok);
    }

    /**
     * 删除文档接口（按 docCode 级联删除：分片、独占知识点、关系、向量、原始文件）
     * DELETE /documents/v1/docCode/{docCode}
     */
    @DeleteMapping("/docCode/{docCode}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteDocument(@PathVariable("docCode") String docCode) {
        log.info("收到文档删除请求: docCode={}", docCode);
        return documentService.deleteByDocCode(docCode).then(Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("docCode", docCode);
            result.put("message", "文档已删除");
            return ResponseEntity.ok(result);
        }));
    }

    /**
     * 文档统计接口（轻量聚合，不返回明细）
     * GET /documents/v1/stats?tenantCode=xxx
     */
    @GetMapping("/stats")
    public Mono<ResponseEntity<DocumentStatsResponse>> getStats(DocumentRequest request) {
        return documentService.getStats(request).map(ResponseEntity::ok);
    }

    /**
     * 健康检查接口
     * GET /documents/v1/health
     */
    @GetMapping("/health")
    public Mono<String> health() {
        return Mono.just("Document service is running");
    }

    /**
     * 知识图谱数据接口（供前端可视化）
     * GET /documents/v1/graph
     */
    @GetMapping("/graph")
    public Mono<ResponseEntity<Map<String, Object>>> getGraph(
            @RequestParam(value = "tenantCode", required = false) String tenantCode,
            @RequestParam(value = "systemType", required = false) String systemType,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        log.info("收到图谱数据请求: tenantCode={}, systemType={}, limit={}", tenantCode, systemType, limit);
        return graphRagService.getGraphData(tenantCode, systemType, limit)
                .map(ResponseEntity::ok);
    }

    /**
     * 构建 DocumentUploadRequest 并调用响应式入库，统一返回 ResponseEntity
     */
    private Mono<ResponseEntity<DocumentResponse>> buildAndUpload(
            MultipartFile file, String title, String description,
            String docCode, String tenantCode, String systemType, String userId) {

        DocumentUploadRequest request = DocumentUploadRequest.builder().file(file)
                .fileName(file.getOriginalFilename()).description(description)
                .title(StringUtils.hasLength(title) ? title : file.getOriginalFilename())
                .documentType(Utils.getFileType(file.getOriginalFilename()))
                .docCode(docCode).tenantCode(tenantCode).systemType(systemType)
                .userId(userId).build();
        return graphRagService.insertKnowledgeDoc(request).map(response -> {
            if ("FAILED".equals(response.getStatus())) {
                log.warn("文档上传失败: {}", response.getErrorMessage());
                return ResponseEntity.badRequest().body(response);
            }
            log.info("文档已提交处理: documentId={}, docCode={}, fileName={}, status={}",
                    response.getDocumentId(), response.getDocCode(), response.getFileName(), response.getStatus());
            return ResponseEntity.ok(response);
        });
    }

}
