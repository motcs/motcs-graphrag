package com.motcs.core.knowledge.record.unanswered;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地知识库无法回复问题后台统计任务
 * 定期汇总最近一段时间内本地知识库无法回答的问题，输出到日志；
 * 明细保存在 unanswered_question 表，可通过统计接口 /documents/v1/unanswered/stats 与
 * 列表接口 /documents/v1/unanswered/list 查询。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-10-12 星期一
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class UnansweredStatScheduler {

    private final UnansweredQuestionRepository unansweredQuestionRepository;

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    public void summarizeUnanswered() {
        try {
            LocalDateTime end = LocalDateTime.now();
            LocalDateTime start = end.minusHours(24);
            List<UnansweredStatics> stats = unansweredQuestionRepository
                    .statsByQuestion(null, start, end).collectList().block();
            if (stats == null || stats.isEmpty()) {
                log.info("本地知识库无法回复问题统计：最近24小时无记录");
                return;
            }
            long total = stats.stream().mapToLong(s -> s.getCnt() == null ? 0L : s.getCnt()).sum();
            StringBuilder sb = new StringBuilder();
            sb.append("最近24小时本地知识库无法回复问题统计：共 ")
                    .append(stats.size()).append(" 个不同问题，累计 ").append(total).append(" 次\n");
            stats.stream().limit(20).forEach(s ->
                    sb.append("  ×").append(s.getCnt()).append("  ").append(s.getQuestion()).append("\n"));
            log.info(sb.toString());
        } catch (Exception e) {
            log.warn("本地知识库无法回复问题后台统计失败: {}", e.getMessage());
        }
    }
}
