package com.motcs.core.knowledge.record.unanswered;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 本地知识库无法回复问题仓库
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-10-12 星期一
 */
public interface UnansweredQuestionRepository extends ReactiveCrudRepository<UnansweredQuestion, Long> {

    /**
     * 统计聚合：按问题分组统计次数与最近时间（数据库聚合，供统计数字/上方展示）
     *
     * @param tenantCode 租户编码，为空表示不限
     * @param startTime  开始时间（含）
     * @param endTime    结束时间（不含）
     */
    @Query("""
            SELECT question AS question, COUNT(*) AS cnt, MAX(create_time) AS last_time
            FROM unanswered_question
            WHERE (:tenantCode IS NULL OR tenant_code = :tenantCode)
              AND create_time >= :startTime AND create_time < :endTime
            GROUP BY question ORDER BY cnt DESC, last_time DESC
            """)
    Flux<UnansweredStatics> statsByQuestion(String tenantCode, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 明细分页：查询对话记录（含 answer/reasoning），倒序
     */
    @Query("""
            SELECT * FROM unanswered_question
            WHERE (:tenantCode IS NULL OR tenant_code = :tenantCode)
              AND create_time >= :startTime AND create_time < :endTime
            ORDER BY create_time DESC
            LIMIT :limit OFFSET :offset
            """)
    Flux<UnansweredQuestion> pageInRange(String tenantCode, LocalDateTime startTime, LocalDateTime endTime,
                                          int limit, long offset);

    /**
     * 明细计数（用于分页）
     */
    @Query("""
            SELECT COUNT(*) FROM unanswered_question
            WHERE (:tenantCode IS NULL OR tenant_code = :tenantCode)
              AND create_time >= :startTime AND create_time < :endTime
            """)
    Mono<Long> countInRange(String tenantCode, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 导出：查询时间范围内全部明细（id/question/answer），倒序
     */
    @Query("""
            SELECT id, question, answer FROM unanswered_question
            WHERE (:tenantCode IS NULL OR tenant_code = :tenantCode)
              AND create_time >= :startTime AND create_time < :endTime
            ORDER BY create_time DESC
            """)
    Flux<UnansweredQuestion> listInRange(String tenantCode, LocalDateTime startTime, LocalDateTime endTime);
}
