package com.motcs.core.document;

import com.motcs.commons.utils.FileUtils;
import com.motcs.commons.utils.Utils;
import com.motcs.core.knowledge.KnowledgeEntity;
import com.motcs.core.knowledge.chunk.DocumentChunk;
import com.motcs.core.knowledge.chunk.DocumentChunkRepository;
import com.motcs.core.knowledge.record.ChatMessage;
import com.motcs.core.knowledge.record.ChatMessageRepository;
import com.motcs.commons.utils.AnyDocConverterUtil;
import com.motcs.commons.utils.EnterpriseChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档管理服务
 * 负责文档的上传、转换、切分、保存到向量库等核心功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final VectorStore vectorStore;
    private final EnterpriseChunker enterpriseChunker;
    private final AnyDocConverterUtil anyDocConverter;
    private final DocumentChunkRepository chunkRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Value("${app.file.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${app.file.max-size:10485760}") // 默认10MB
    private long maxFileSize;

    /**
     * 上传上下文：异步处理过程中传递的状态信息
     */
    @lombok.Data
    public static class UploadContext {
        private String documentId;
        private String placeholderId;
        private String filePath;
        private List<String> chunkIds = new ArrayList<>();
        private DocumentResponse response;
    }

    /**
     * 上传文档初始化（同步部分）：校验 → 清理旧数据 → 保存文件 → 创建占位分片(PROCESSING)
     * <p>
     * 不触发异步处理，返回 UploadContext 供调用方编排异步流程。
     * 非 SUCCESS 文档不参与知识搜索（向量检索过滤 status=SUCCESS）。
     */
    public Mono<UploadContext> uploadDocument(DocumentUploadRequest request) {
        return Mono.fromCallable(() -> {
            MultipartFile file = request.getFile();
            FileUtils.validateFile(file, maxFileSize);

            // 重新上传同 docCode：先清理旧数据（向量+分片+图谱）
            if (request.getDocCode() != null && !request.getDocCode().isBlank()) {
                deleteByDocCodeSync(request.getDocCode());
            }

            String filePath = FileUtils.saveFile(file, uploadDir, request.getTenantCode());
            log.info("文件已保存到: {}", filePath);

            // 创建占位分片（PROCESSING），文档列表可见但不参与搜索
            String documentId = UUID.randomUUID().toString();
            String placeholderId = UUID.randomUUID().toString();
            chunkRepository.save(DocumentChunk.builder()
                    .id(placeholderId).documentId(documentId)
                    .docCode(request.getDocCode()).tenantCode(request.getTenantCode())
                    .systemType(request.getSystemType()).enabled(false)
                    .fileName(request.getFileName()).userId(request.getUserId())
                    .content("").chunkIndex(-1).status("PROCESSING").build());

            UploadContext context = new UploadContext();
            context.setDocumentId(documentId);
            context.setPlaceholderId(placeholderId);
            context.setFilePath(filePath);
            context.setResponse(DocumentResponse.builder().documentId(FileUtils.parseDocumentId(documentId))
                    .docCode(request.getDocCode()).tenantCode(request.getTenantCode())
                    .systemType(request.getSystemType()).enabled(false).fileName(request.getFileName())
                    .title(request.getTitle()).description(request.getDescription())
                    .chunkCount(0).status("PROCESSING").uploadTime(LocalDateTime.now())
                    .fileSize(file.getSize()).filePath(filePath).userId(request.getUserId()).build());

            return context;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.error("文档上传初始化失败: {}", e.getMessage(), e);
            UploadContext ctx = new UploadContext();
            ctx.setResponse(DocumentResponse.builder().status("FAILED")
                    .errorMessage(e.getMessage()).uploadTime(LocalDateTime.now()).build());
            return Mono.just(ctx);
        });
    }

    /**
     * 异步文档处理核心逻辑：转换 → 切分 → 向量入库 → 删除占位 → 保存真实分片(SUCCESS)
     * 由调用方在异步线程中订阅，并可追加图谱构建等后续步骤。
     */
    public Mono<Void> processDocumentAsync(DocumentUploadRequest request, UploadContext context) {
        return Mono.fromRunnable(() -> {
            try {
                MultipartFile file = request.getFile();
                String documentId = context.getDocumentId();

                // 1. 转换
                String originalContent = FileUtils.readFileContent(file);
                String markdownContent;
                try {
                    markdownContent = anyDocConverter.convertToMarkdown(request.getFileName(), file.getBytes());
                    log.info("文档转换成功，原长度: {}, Markdown长度: {}", originalContent.length(), markdownContent.length());
                } catch (Exception e) {
                    log.warn("文档转换失败，使用原始内容: {}", e.getMessage());
                    markdownContent = anyDocConverter.convertTextToMarkdown(originalContent);
                }

                // 1.5 内容为空检查：转换后仍无文字 → 明确报错
                //（常见于扫描件/图片型 PDF（无文字层）、加密文档、损坏文件或格式不支持）
                if (markdownContent == null || markdownContent.isBlank()) {
                    log.error("文档未提取到文字内容，fileName={}, originalLength={}",
                            request.getFileName(), originalContent.length());
                    throw new RuntimeException("文档未提取到文字内容（可能为扫描件/图片型 PDF、加密文档或格式不支持）："
                            + request.getFileName());
                }

                // 2. 企业级双层切分（细粒度检索 + 粗粒度推理）
                List<EnterpriseChunker.Chunk> allChunks = enterpriseChunker.chunk(markdownContent);
                List<EnterpriseChunker.Chunk> fineChunks = allChunks.stream()
                        .filter(c -> "FINE".equals(c.getChunkType())).toList();
                if (fineChunks.isEmpty()) throw new RuntimeException("文档切分失败：未生成可检索的细粒度分片");
                int coarseCount = allChunks.size() - fineChunks.size();
                log.info("企业级切分完成：细粒度 {} 片，粗粒度 {} 片", fineChunks.size(), coarseCount);

                // 3. 构建向量库 Document（仅细粒度切片入库，用于检索召回）
                List<Document> documents = new ArrayList<>();
                for (int i = 0; i < fineChunks.size(); i++) {
                    EnterpriseChunker.Chunk c = fineChunks.get(i);
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", request.getFileName());
                    metadata.put("title", request.getTitle() != null ? request.getTitle() : request.getFileName());
                    if (request.getDescription() != null) metadata.put("description", request.getDescription());
                    metadata.put("documentId", documentId);
                    if (request.getDocCode() != null) metadata.put("docCode", request.getDocCode());
                    metadata.put("tenantCode", request.getTenantCode());
                    metadata.put("systemType", request.getSystemType());
                    metadata.put("enabled", true);
                    metadata.put("status", "PROCESSING");
                    metadata.put("chunkIndex", i);
                    metadata.put("pageNumber", c.getPageNumber());
                    metadata.put("chunkType", "FINE");
                    metadata.put("parentChunkId", c.getParentChunkId());
                    metadata.put("tokenSize", c.getTokenSize());
                    if (c.getTitleHierarchy() != null && !c.getTitleHierarchy().isEmpty()) {
                        metadata.put("titleHierarchy", String.join(" > ", c.getTitleHierarchy()));
                    }
                    metadata.put("uploadTime", LocalDateTime.now().toString());
                    metadata.put("totalChunks", fineChunks.size());
                    metadata.put("originalFormat", Utils.getFileExtension(request.getFileName()));
                    metadata.put("convertedTo", "markdown");
                    metadata.put("fileSize", file.getSize());
                    documents.add(new Document(c.getContent(), metadata));
                }

                // 4. 向量入库（仅细粒度）
                this.vectorStore.add(documents);
                context.setChunkIds(documents.stream().map(Document::getId).toList());
                log.info("文档已成功添加到向量库，共 {} 个细粒度片段", documents.size());

                // 5. 删除占位分片
                chunkRepository.deleteById(context.getPlaceholderId());

                // 6. 保存所有分片到图谱（细粒度 + 粗粒度）
                saveChunksToGraph(allChunks, documents, documentId, request.getDocCode(),
                        request.getFileName(), request.getTenantCode(), request.getSystemType(),
                        request.getUserId());

                log.info("文档处理完成: documentId={}, fileName={}, 细粒度={}, 粗粒度={}",
                        documentId, request.getFileName(), fineChunks.size(), coarseCount);
            } catch (Exception e) {
                log.error("文档异步处理失败: {}", e.getMessage(), e);
                failUpload(context, e.getMessage());
                throw new RuntimeException(e);
            }
        })
        // 整个处理块均为阻塞操作（文件转换/切分/向量入库/分片库JPA调用），
        // 显式切到 boundedElastic 线程池执行，避免在 Netty 事件循环等非阻塞线程上
        // 直接执行阻塞调用（如 chunkRepository.deleteById）导致线程匮乏
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }

    /**
     * 处理失败清理：删除已入库向量，所有分片标记 FAILED
     */
    public void failUpload(UploadContext context, String errorMessage) {
        try {
            if (context.getChunkIds() != null && !context.getChunkIds().isEmpty()) {
                vectorStore.delete(context.getChunkIds());
                log.info("已清理失败文档的 {} 条向量", context.getChunkIds().size());
            }
            if (context.getDocumentId() != null) {
                chunkRepository.updateStatusByDocumentId(context.getDocumentId(), "FAILED", errorMessage);
                log.info("已标记 documentId={} 为 FAILED: {}", context.getDocumentId(), errorMessage);
            }
        } catch (Exception ex) {
            log.warn("失败清理异常: {}", ex.getMessage());
        }
    }

    /**
     * 图谱构建完成后，将文档所有分片标记为 SUCCESS（向量节点同步更新）
     */
    public void markDocumentSuccess(String documentId) {
        if (documentId != null) {
            chunkRepository.updateStatusByDocumentId(documentId, "SUCCESS", null);
            log.info("文档处理完成，已标记 documentId={} 为 SUCCESS", documentId);
        }
    }

    /**
     * 同步删除文档（重新上传前的清理：向量+分片+图谱关系）
     */
    private void deleteByDocCodeSync(String docCode) {
        try {
            List<String> chunkIds = chunkRepository.findChunkIdsByDocCode(docCode);
            if (!chunkIds.isEmpty()) vectorStore.delete(chunkIds);
            chunkRepository.deleteChunksByDocCode(docCode);
            log.info("重新上传前已清理 docCode={} 的旧数据（{} 个分片）", docCode, chunkIds.size());
        } catch (Exception e) {
            log.warn("重新上传前清理旧数据失败: {}", e.getMessage());
        }
    }

    /**
     * 按 docCode 级联删除文档及其所有关联数据
     * （docCode 是用户指定的业务编码，前后端一致，比 documentId 更可靠）
     * 1. 删除仅被该文档引用的知识点（含 MENTIONS / RELATE_TO 关系）
     * 2. 删除该文档的所有分片（DETACH DELETE 清除剩余 MENTIONS 关系）
     * 3. 从向量库删除对应向量
     * 4. 删除上传的原始文件
     */
    public Mono<Void> deleteByDocCode(String docCode) {
        return Mono.fromCallable(() -> {
            // 1. 查询分片ID
            List<String> chunkIds = chunkRepository.findChunkIdsByDocCode(docCode);
            if (chunkIds.isEmpty()) {
                log.warn("未找到 docCode={} 的文档分片", docCode);
                return null;
            }

            // 获取文件名和租户（用于删除原始文件）
            String fileName = null, tenantCode = null;
            for (DocumentChunk c : chunkRepository.findAllById(chunkIds)) {
                if (c.getFileName() != null) fileName = c.getFileName();
                if (c.getTenantCode() != null) tenantCode = c.getTenantCode();
                if (fileName != null && tenantCode != null) break;
            }

            // 2. 查询并删除仅被该文档引用的知识点
            List<KnowledgeEntity> exclusive = chunkRepository.findExclusiveEntitiesByDocCode(docCode);
            if (!exclusive.isEmpty()) {
                List<Long> entityIds = exclusive.stream()
                        .map(KnowledgeEntity::getId).filter(Objects::nonNull).toList();
                chunkRepository.deleteEntitiesByIds(entityIds);
                log.info("已删除 {} 个独占知识点", entityIds.size());
            }

            // 3. 删除分片（DETACH DELETE 清除剩余 MENTIONS 关系）
            chunkRepository.deleteChunksByDocCode(docCode);
            log.info("已删除 docCode={} 的 {} 个分片", docCode, chunkIds.size());

            // 4. 从向量库删除
            try {
                vectorStore.delete(chunkIds);
                log.info("已从向量库删除 {} 条向量", chunkIds.size());
            } catch (Exception e) {
                log.warn("从向量库删除失败: {}", e.getMessage());
            }

            // 5. 删除原始文件
            if (fileName != null && tenantCode != null) {
                Path filePath = Paths.get(uploadDir, "T" + tenantCode, fileName);
                try {
                    Files.deleteIfExists(filePath);
                    log.info("已删除原始文件: {}", filePath);
                } catch (Exception e) {
                    log.warn("删除原始文件失败: {}", e.getMessage());
                }
            }

            // 6. 异步标记对话记录中引用该文档的来源为"已删除"（分页处理，不阻塞删除流程）
            markSourcesDeletedAsync(docCode);

            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * 遍历所有对话记录，将引用指定 docCode 的来源标记为已删除。
     * 历史对话保持删除时的状态，文档重新上传不影响历史记录。
     * 异步标记对话记录中引用指定 docCode 的来源为"已删除"（分页处理，每批200条，避免全量查询OOM）
     * 历史对话保持删除时的状态，文档重新上传不影响历史记录。
     */
    public void markSourcesDeletedAsync(String docCode) {
        Mono.fromRunnable(() -> {
            int offset = 0;
            int batchSize = 200;
            int totalUpdated = 0;
            while (true) {
                List<ChatMessage> batch = chatMessageRepository
                        .findBySourcesDocCode(docCode, batchSize, offset)
                        .collectList().block();
                if (batch == null || batch.isEmpty()) break;
                for (ChatMessage record : batch) {
                    JsonNode sources = record.getSources();
                    if (sources == null || !sources.isArray()) continue;
                    boolean changed = false;
                    for (JsonNode src : sources) {
                        JsonNode srcDocCode = src.get("docCode");
                        if (srcDocCode != null && docCode.equals(srcDocCode.asString())) {
                            ((ObjectNode) src).put("deleted", true);
                            changed = true;
                        }
                    }
                    if (changed) {
                        chatMessageRepository.save(record).block();
                        totalUpdated++;
                    }
                }
                offset += batch.size();
                if (batch.size() < batchSize) break;
            }
            log.info("异步标记完成: docCode={}, 共更新 {} 条对话记录", docCode, totalUpdated);
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 将向量库中的 Document 同步保存为图谱 DocumentChunk 节点
     * （VectorStore 写入的节点缺少 docCode / documentId 等业务属性，这里通过 Repository 补齐）
     * 将所有切片（细粒度+粗粒度）保存到图谱
     * 细粒度切片使用向量库生成的 ID，粗粒度切片使用 EnterpriseChunker 生成的 ID
     */
    private void saveChunksToGraph(List<EnterpriseChunker.Chunk> allChunks, List<Document> fineDocuments,
                                   String documentId, String docCode, String fileName,
                                   String tenantCode, String systemType, String userId) {
        List<DocumentChunk> coarseChunks = new ArrayList<>();
        int fineIndex = 0;
        int fineUpdated = 0;

        for (EnterpriseChunker.Chunk c : allChunks) {
            String titleHierarchy = c.getTitleHierarchy() != null
                    ? String.join(" > ", c.getTitleHierarchy()) : null;

            if ("FINE".equals(c.getChunkType())) {
                // 细粒度切片：向量库已创建节点，用 Cypher 设置 SDN 需要的无前缀属性（不覆盖 embedding）
                String nodeId = fineDocuments.get(fineIndex).getId();
                chunkRepository.updateFineChunkGraphProps(nodeId, documentId, docCode,
                        tenantCode, systemType, true, fileName, userId, c.getContent(),
                        fineIndex, c.getPageNumber(), "FINE", c.getParentChunkId(),
                        c.getPrevChunkId(), c.getNextChunkId(), titleHierarchy, c.getTokenSize(),
                        "PROCESSING");
                fineIndex++;
                fineUpdated++;
            } else {
                // 粗粒度切片：新建节点
                DocumentChunk chunk = DocumentChunk.builder()
                        .id(c.getChunkId()).documentId(documentId)
                        .docCode(docCode).tenantCode(tenantCode).systemType(systemType).enabled(true)
                        .fileName(fileName).content(c.getContent()).chunkIndex(-1)
                        .userId(userId).pageNumber(c.getPageNumber())
                        .chunkType("COARSE").parentChunkId(null)
                        .prevChunkId(c.getPrevChunkId()).nextChunkId(c.getNextChunkId())
                        .titleHierarchy(titleHierarchy).tokenSize(c.getTokenSize())
                        .status("PROCESSING").build();
                coarseChunks.add(chunk);
            }
        }

        if (!coarseChunks.isEmpty()) {
            this.chunkRepository.saveAll(coarseChunks);
        }
        log.info("已同步图谱：细粒度更新 {} 个节点属性，粗粒度新建 {} 个节点", fineUpdated, coarseChunks.size());
    }

    /**
     * 查询文档列表（响应式），支持按租户隔离
     *
     * @param tenantCode 租户编码，为空则查询全部
     * @param systemType 系统类型，为空则不过滤
     * @return 文档响应列表 Mono
     */
    public Mono<List<DocumentResponse>> queryDocuments(String tenantCode, String systemType) {
        return Mono.fromCallable(() -> {
            List<DocumentSummary> summaries = this.chunkRepository.findDocumentSummaries(
                    tenantCode != null && !tenantCode.isBlank() ? tenantCode : "default",
                    systemType != null && !systemType.isBlank() ? systemType : "default"
            );
            return summaries.stream().map(s -> DocumentResponse.builder()
                    .documentId(FileUtils.parseDocumentId(s.documentId()))
                    .docCode(s.docCode())
                    .tenantCode(s.tenantCode())
                    .systemType(s.systemType())
                    .enabled(s.enabled() == null || s.enabled())
                    .fileName(s.fileName())
                    .title(s.title() != null ? s.title() : s.fileName())
                    .description(s.description())
                    .chunkCount(s.chunkCount() != null ? s.chunkCount().intValue() : 0)
                    .status(s.status() != null ? s.status() : "UNKNOWN")
                    .errorMessage(s.errorMessage())
                    .fileSize(s.fileSize() != null ? s.fileSize() : 0L)
                    .uploadTime(FileUtils.parseUploadTime(s.uploadTime()))
                    .userId(s.userId())
                    .build()
            ).collect(Collectors.toList());
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.error("查询文档列表失败: {}", e.getMessage(), e);
            return Mono.just(List.of());
        });
    }

    /**
     * 按租户统计文档数和分片数（轻量聚合查询，不返回明细）
     */
    public Mono<DocumentStatsResponse> getStats(DocumentRequest request) {
        return Mono.fromCallable(() -> {
            String tenantCode = request != null ? request.getTenantCode() : null;
            DocumentStatsResponse stats = chunkRepository.countByTenant(tenantCode);
            if (stats == null) {
                stats = DocumentStatsResponse.builder().docCount(0L).chunkCount(0L).build();
            }
            return stats;
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
            log.error("统计查询失败: {}", e.getMessage());
            return Mono.just(DocumentStatsResponse.builder().docCount(0L).chunkCount(0L).build());
        });
    }

}