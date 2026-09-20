package com.motcs.core.document.info;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import java.time.LocalDateTime;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文档元数据仓库（document_info，MySQL R2DBC）。
 * 文档列表/搜索/筛选直接查本表，不走 Neo4j。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-19 星期六
 */
public interface DocumentInfoRepository extends ReactiveCrudRepository<DocumentInfo, Long> {

    Mono<DocumentInfo> findByDocCode(String docCode);

    Mono<Boolean> existsByDocCode(String docCode);

    /**
     * 按前缀（如 20260920D）倒序取最大 doc_code，用于新上传自动生成业务编码。
     */
    @Query("SELECT doc_code FROM document_info WHERE doc_code LIKE :prefix ORDER BY doc_code DESC LIMIT 1")
    Mono<String> findMaxDocCodeByPrefix(@Param("prefix") String prefix);

    Mono<Void> deleteByDocCode(String docCode);

    /**
     * 处理成功：更新状态为 SUCCESS、分片数、启用标志。
     */
    @Modifying
    @Query("UPDATE document_info SET status='SUCCESS', chunk_count=:chunkCount, enabled=TRUE, error_message=NULL, updated_time=NOW() WHERE doc_code=:docCode")
    Mono<Long> markSuccess(@Param("docCode") String docCode, @Param("chunkCount") int chunkCount);

    /**
     * 处理失败：更新状态为 FAILED、错误信息。
     */
    @Modifying
    @Query("UPDATE document_info SET status='FAILED', error_message=:errorMessage, enabled=FALSE, updated_time=NOW() WHERE doc_code=:docCode")
    Mono<Long> markFailed(@Param("docCode") String docCode, @Param("errorMessage") String errorMessage);

    /**
     * 统计某租户的文档数和分片数（轻量聚合，替代 Neo4j 聚合）。
     */
    @Query("SELECT COUNT(*) FROM document_info WHERE (:tenantCode='' OR tenant_code=:tenantCode) AND status='SUCCESS'")
    Mono<Long> countSuccessByTenant(@Param("tenantCode") String tenantCode);

    @Query("SELECT COALESCE(SUM(chunk_count),0) FROM document_info WHERE (:tenantCode='' OR tenant_code=:tenantCode) AND status='SUCCESS'")
    Mono<Long> sumChunksByTenant(@Param("tenantCode") String tenantCode);

    /**
     * 表是否为空（启动时判断是否需要从 Neo4j 迁移历史数据）
     */
    @Query("SELECT COUNT(*) FROM document_info")
    Mono<Long> countAll();

    /**
     * Stuck PROCESSING docs: older than threshold and never auto-retried.
     */
    @Query("SELECT * FROM document_info WHERE status='PROCESSING' AND created_time < :threshold AND (retry_count IS NULL OR retry_count = 0)")
    Flux<DocumentInfo> findStuckProcessing(@Param("threshold") LocalDateTime threshold);

    /**
     * Mark as auto-retried once to avoid repeat retries.
     */
    @Modifying
    @Query("UPDATE document_info SET retry_count=1, updated_time=NOW() WHERE doc_code=:docCode")
    Mono<Long> markRetried(@Param("docCode") String docCode);

}
