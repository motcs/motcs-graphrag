package com.motcs.core.document;

/**
 * 文档列表摘要（Neo4j 聚合查询直接返回，不拉取 embedding/content）
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
) {}
