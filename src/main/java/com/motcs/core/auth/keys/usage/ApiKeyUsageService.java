package com.motcs.core.auth.keys.usage;

import com.motcs.commons.utils.Utils;
import com.motcs.core.auth.keys.usage.summary.ApiKeyUsageSummaryRepository;
import com.motcs.core.auth.keys.usage.summary.UsageOverviewRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API Key 使用监控服务：
 * <ul>
 *   <li>明细：每次 Key 对话的 token 消耗写入 api_key_usage（保留流水，供单 Key 明细弹窗）；</li>
 *   <li>汇总：同时把本次用量累加到 api_key_usage_summary（按 Key 聚合快照），
 *       监控总览直接查汇总表，按 total_tokens 降序分页，避免每次全量扫描明细表内存聚合。</li>
 * </ul>
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class ApiKeyUsageService {

    private final ApiKeyUsageRepository apiKeyUsageRepository;
    private final ApiKeyUsageSummaryRepository summaryRepository;

    /**
     * 应用启动后：若汇总表为空但明细表有数据，则从明细表一次性重建汇总。
     * 用于首次上线升级时把历史用量迁移到汇总表，避免历史数据丢失。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initSummaryIfEmpty() {
        summaryRepository.countSummary().defaultIfEmpty(0L).flatMap(cnt -> {
            if (cnt > 0) {
                return Mono.empty();
            }
            log.info("用量汇总表为空，开始从 api_key_usage 明细表重建...");
            return summaryRepository.truncate()
                    .then(summaryRepository.rebuildFromDetail())
                    .doOnSuccess(n -> log.info("用量汇总表重建完成，共 {} 个 Key 的汇总记录", n))
                    .onErrorResume(e -> {
                        log.warn("用量汇总表重建失败（不影响主流程，后续 record 会继续累加）: {}", e.getMessage());
                        return Mono.empty();
                    });
        }).subscribe(v -> {
        }, e -> log.warn("用量汇总初始化异常: {}", e.getMessage()));
    }

    /**
     * 记录一次 API Key 对话的 token 消耗：
     * 1) 写明细流水（api_key_usage）；2) 同步累加到汇总表（api_key_usage_summary）。
     * 流结束后异步调用，不阻塞响应。
     */
    public Mono<Void> record(Long apiKeyId, String userId, String sessionId, String model,
                             Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        if (ObjectUtils.isEmpty(apiKeyId) || ObjectUtils.isEmpty(totalTokens) || totalTokens <= 0) {
            // token 缺失（如异常中断未拿到 usage）时不落库，避免脏数据
            return Mono.empty();
        }
        int p = Utils.nullToZero(promptTokens);
        int c = Utils.nullToZero(completionTokens);
        int t = Utils.nullToZero(totalTokens);
        ApiKeyUsage usage = ApiKeyUsage.builder().apiKeyId(apiKeyId).userId(userId)
                .sessionId(sessionId).model(model).promptTokens(p)
                .completionTokens(c).totalTokens(t).createdTime(LocalDateTime.now()).build();
        // 明细落库 + 汇总累加，两者并行；任一项失败不影响另一个
        Mono<Void> detail = this.apiKeyUsageRepository.save(usage).then();
        Mono<Void> summary = this.summaryRepository.incrementUsage(apiKeyId, p, c, t, LocalDateTime.now()).then();
        return Mono.when(detail, summary).doOnSuccess(v ->
                log.info("API Key 用量已记录并累加汇总: apiKeyId={}, userId={}, totalTokens={}", apiKeyId, userId, t));
    }

    /**
     * 按 Key 汇总使用情况：直接读汇总表（快照，O(1)）。
     */
    public Mono<Map<String, Object>> summary(Long apiKeyId) {
        return this.summaryRepository.findByApiKeyId(apiKeyId)
                .map(s -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("totalCalls", s.getTotalCalls() == null ? 0 : s.getTotalCalls());
                    result.put("promptTokens", s.getPromptTokens() == null ? 0 : s.getPromptTokens());
                    result.put("completionTokens", s.getCompletionTokens() == null ? 0 : s.getCompletionTokens());
                    result.put("totalTokens", s.getTotalTokens() == null ? 0 : s.getTotalTokens());
                    return result;
                })
                .defaultIfEmpty(Map.of(
                        "totalCalls", 0L, "promptTokens", 0L, "completionTokens", 0L, "totalTokens", 0L));
    }

    /**
     * 按 Key 分页查询调用明细（明细表流水，监控明细弹窗用）
     */
    public Flux<ApiKeyUsage> list(Long apiKeyId, Pageable pageable) {
        return this.apiKeyUsageRepository.findByApiKeyIdOrderByCreatedTimeDesc(apiKeyId, pageable);
    }

    /**
     * API Key 管理列表分页：api_key LEFT JOIN 汇总表，按创建时间降序，
     * 每行直接带出累计用量（避免前端逐行再调 usage-summary 造成 N+1）。
     */
    public Mono<Map<String, Object>> listApiKeysPage(Pageable pageable) {
        Mono<Long> totalMono = this.summaryRepository.countAllKeys().defaultIfEmpty(0L);
        Flux<UsageOverviewRow> rows = this.summaryRepository.findApiKeyList(pageable);
        return Mono.zip(totalMono, rows.collectList()).map(t -> {
            long totalKeys = t.getT1();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", t.getT2());
            result.put("number", pageable.getPageNumber());
            result.put("size", pageable.getPageSize());
            result.put("totalElements", totalKeys);
            int totalPages = pageable.getPageSize() == 0 ? 0
                    : (int) ((totalKeys + pageable.getPageSize() - 1) / pageable.getPageSize());
            result.put("totalPages", totalPages);
            return result;
        });
    }

    /**
     * 手动重建汇总表：清空后从明细表全量聚合。
     * 用于升级后把历史用量灌进汇总表，或数据不一致时修复。
     */
    public Mono<Long> rebuildSummary() {
        log.info("手动触发用量汇总表重建...");
        return summaryRepository.truncate()
                .then(summaryRepository.rebuildFromDetail())
                .doOnSuccess(n -> log.info("用量汇总表重建完成，共 {} 个 Key", n));
    }

    /**
     * 用量监控总览：直接查汇总表 JOIN api_key，按 total_tokens 降序分页。
     * <p>
     * 返回：全局合计卡片数字 + 当前页数据行 + 分页信息（totalElements/totalPages/number/size）。
     *
     * @param pageable 分页参数（默认 size=10，由 Controller 设定）
     */
    public Mono<Map<String, Object>> overview(Pageable pageable) {
        Mono<Long> totalMono = this.summaryRepository.countAllKeys().defaultIfEmpty(0L);
        Mono<UsageOverviewRow> totalsMono = this.summaryRepository.globalTotals()
                .defaultIfEmpty(new UsageOverviewRow(null, null, null, null,
                        null, null, 0L, 0L, 0L,
                        0L, null, null));
        Flux<UsageOverviewRow> rows = this.summaryRepository.findOverview(pageable);
        return Mono.zip(totalMono, totalsMono).flatMap(t -> {
            long totalKeys = t.getT1();
            UsageOverviewRow g = t.getT2();
            return rows.collectList().map(list -> {
                Map<String, Object> result = new LinkedHashMap<>();
                // 顶部统计卡片
                result.put("totalKeys", totalKeys);
                result.put("totalCalls", g.totalCalls() == null ? 0L : g.totalCalls());
                result.put("promptTokens", g.promptTokens() == null ? 0L : g.promptTokens());
                result.put("completionTokens", g.completionTokens() == null ? 0L : g.completionTokens());
                result.put("totalTokens", g.totalTokens() == null ? 0L : g.totalTokens());
                // 当前页数据
                result.put("content", list);
                // 分页信息
                result.put("number", pageable.getPageNumber());
                result.put("size", pageable.getPageSize());
                result.put("totalElements", totalKeys);
                int totalPages = pageable.getPageSize() == 0 ? 0
                        : (int) ((totalKeys + pageable.getPageSize() - 1) / pageable.getPageSize());
                result.put("totalPages", totalPages);
                return result;
            });
        });
    }

}
