package com.motcs.core.document;

/**
 * 文档列表摘要（Neo4j 聚合查询直接返回，不拉取 embedding/content）
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public record DocumentSummary(
        String documentId,
        Long chunkCount,
        String docCode,
        String fileName,
        String title,
        String description,
        String status,
        String errorMessage,
        String userId,
        String tenantCode,
        String systemType,
        Boolean enabled,
        String uploadTime,
        Long fileSize
) {
}
