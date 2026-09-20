package com.motcs.core.document;

import com.motcs.commons.utils.ByteArrayMultipartFile;
import com.motcs.commons.utils.Utils;
import com.motcs.core.document.info.DocumentInfo;
import com.motcs.core.document.info.DocumentInfoRepository;
import com.motcs.core.knowledge.graph.GraphRagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡住文档自动重跑：PROCESSING 超过 2 小时且未重跑过的文档，
 * 后台只清向量库与图谱，重新从磁盘文件处理；源文件保留、不重建 document_info。
 * 找不到源文件则标记 FAILED 并记录原因。每个文档只自动重跑一次（retry_count=1）。
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class DocumentReprocessScheduler {

    private final DocumentInfoRepository documentInfoRepository;
    private final GraphRagService graphRagService;

    @Value("${app.file.upload-dir:./uploads}")
    private String uploadDir;

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    public void retryStuckDocuments() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(2);
        List<DocumentInfo> stuck;
        try {
            stuck = documentInfoRepository.findStuckProcessing(threshold).collectList().block();
        } catch (Exception e) {
            log.warn("查询卡住文档失败: {}", e.getMessage());
            return;
        }
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        log.info("发现 {} 个处理中超过2小时的文档，开始自动重跑", stuck.size());
        for (DocumentInfo info : stuck) {
            try {
                reprocessOne(info);
            } catch (Exception e) {
                log.error("重跑文档失败 docCode={}: {}", info.getDocCode(), e.getMessage(), e);
            }
        }
    }

    private void reprocessOne(DocumentInfo info) {
        String docCode = info.getDocCode();
        // 先标记已重跑一次，避免重复
        try {
            documentInfoRepository.markRetried(docCode).block();
        } catch (Exception e) {
            log.warn("标记重跑标记失败 docCode={}: {}", docCode, e.getMessage());
        }

        Path path = locateFile(info);
        if (path == null || !Files.exists(path)) {
            String reason = "源文件不存在，无法自动重跑: " + (path != null ? path : "未知路径");
            log.error(reason);
            markFailedQuietly(docCode, reason);
            return;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (Exception e) {
            String reason = "读取源文件失败: " + e.getMessage();
            log.error(reason);
            markFailedQuietly(docCode, reason);
            return;
        }

        // 文件名用存储名（带扩展名，供转换与下游元数据）
        String storedName = (info.getStoredFileName() != null && !info.getStoredFileName().isBlank())
                ? info.getStoredFileName() : info.getFileName();
        MultipartFile file = new ByteArrayMultipartFile("file", storedName,
                "application/octet-stream", bytes);

        DocumentUploadRequest request = DocumentUploadRequest.builder()
                .file(file).fileName(storedName)
                .title(info.getTitle()).description(info.getDescription())
                .documentType(Utils.getFileType(storedName))
                .docCode(docCode).tenantCode(info.getTenantCode())
                .systemType(info.getSystemType()).userId(info.getUserId())
                .build();

        log.info("开始重跑文档 docCode={}, file={}", docCode, storedName);
        graphRagService.reprocessKnowledgeDoc(request, info.getDocumentId()).block();
    }

    private Path locateFile(DocumentInfo info) {
        // 优先用记录的完整磁盘路径
        if (info.getFilePath() != null && !info.getFilePath().isBlank()) {
            Path p = Paths.get(info.getFilePath());
            if (Files.exists(p)) {
                return p;
            }
            return p;
        }
        // 兜底：uploadDir/T<tenant>/storedFileName
        if (info.getStoredFileName() != null && info.getTenantCode() != null) {
            return Paths.get(uploadDir, "T" + info.getTenantCode(), info.getStoredFileName());
        }
        return null;
    }

    private void markFailedQuietly(String docCode, String reason) {
        try {
            documentInfoRepository.markFailed(docCode, reason).block();
        } catch (Exception ex) {
            log.warn("标记 FAILED 失败 docCode={}: {}", docCode, ex.getMessage());
        }
    }
}
