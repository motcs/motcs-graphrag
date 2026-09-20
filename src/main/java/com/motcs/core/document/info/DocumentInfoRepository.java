package com.motcs.core.document.info;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
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

}
