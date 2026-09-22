package com.motcs.core.auth.keys.usage;

import com.motcs.commons.ContextUtil;
import com.motcs.commons.base.DatabaseService;
import com.motcs.commons.utils.ParameterSql;
import com.motcs.commons.utils.Utils;
import com.motcs.core.auth.keys.usage.quota.ModelPricing;
import com.motcs.core.auth.keys.usage.session.ChatUsageRow;
import com.motcs.core.auth.keys.usage.session.ChatUsageSummaryRow;
import com.motcs.core.auth.keys.usage.summary.ApiKeyUsageSummaryRepository;
import com.motcs.core.auth.keys.usage.summary.UsageOverviewRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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
public class ApiKeyUsageService extends DatabaseService {

    private final ApiKeyUsageRepository apiKeyUsageRepository;
    private final ApiKeyUsageSummaryRepository summaryRepository;

    /**
     * 应用启动后：若汇总表为空但明细表有数据，则从明细表一次性重建汇总。
     * 用于首次上线升级时把历史用量迁移到汇总表，避免历史数据丢失。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initSummaryIfEmpty() {
        this.summaryRepository.countSummary().defaultIfEmpty(0L).flatMap(cnt -> {
            if (cnt > 0) {
                return Mono.empty();
            }
            log.debug("用量汇总表为空，开始从 api_key_usage 明细表重建...");
            return this.summaryRepository.truncate()
                    .then(this.summaryRepository.rebuildFromDetail())
                    .flatMap(n -> this.summaryRepository.syncUsedQuota().thenReturn(n))
                    .doOnSuccess(n -> log.debug("用量汇总表重建完成，共 {} 个 Key 的汇总记录", n))
                    .onErrorResume(e -> {
                        log.warn("用量汇总表重建失败（不影响主流程，后续 record 会继续累加）: {}", e.getMessage());
                        return Mono.empty();
                    });
        }).subscribe(_ -> {
        }, e -> log.warn("用量汇总初始化异常: {}", e.getMessage()));
    }

    /**
     * 记录一次 API Key 对话的 token 消耗：
     * 1) 写明细流水（api_key_usage）；2) 同步累加到汇总表（api_key_usage_summary）。
     * 流结束后异步调用，不阻塞响应。
     */
    public Mono<Void> record(Long apiKeyId, String userId, String sessionId, String model,
                             Integer promptTokens, Integer completionTokens, Integer totalTokens, Integer cacheTokens) {
        if (ObjectUtils.isEmpty(apiKeyId) || ObjectUtils.isEmpty(totalTokens) || totalTokens <= 0) {
            // token 缺失（如异常中断未拿到 usage）时不落库，避免脏数据
            return Mono.empty();
        }
        int p = Utils.nullToZero(promptTokens);
        int c = Utils.nullToZero(completionTokens);
        int t = Utils.nullToZero(totalTokens);
        int cache = Utils.nullToZero(cacheTokens);
        int uncached = Math.max(p - cache, 0);
        ModelPricing.Price price = ModelPricing.of(model);
        double inputCost = uncached / 1000.0 * price.inPerK() + cache / 1000.0 * price.cachePerK();
        double outputCost = c / 1000.0 * price.outPerK();
        ApiKeyUsage usage = ApiKeyUsage.builder().apiKeyId(apiKeyId).userId(userId)
                .sessionId(sessionId).model(model).promptTokens(p)
                .completionTokens(c).totalTokens(t).cacheTokens(cache).createdTime(LocalDateTime.now()).build();
        // 明细落库 + 汇总累加，两者并行；任一项失败不影响另一个
        Mono<Void> detail = this.apiKeyUsageRepository.save(usage).then();
        Mono<Void> summary = this.summaryRepository.incrementUsage(apiKeyId, p, c, t, cache, inputCost, outputCost, LocalDateTime.now()).then();
        return Mono.when(detail, summary).doOnSuccess(_ ->
                log.debug("API Key 用量已记录并累加汇总: apiKeyId={}, userId={}, totalTokens={}", apiKeyId, userId, t));
    }

    /**
     * 按 Key 汇总使用情况：直接读汇总表（快照，O(1)）。
     */
    public Mono<Map<String, Object>> summary(Long apiKeyId) {
        return this.summaryRepository.findByApiKeyId(apiKeyId).map(s -> {
            Map<String, Object> result = new HashMap<>();
            result.put("totalCalls", s.getTotalCalls() == null ? 0 : s.getTotalCalls());
            result.put("promptTokens", s.getPromptTokens() == null ? 0 : s.getPromptTokens());
            result.put("completionTokens", s.getCompletionTokens() == null ? 0 : s.getCompletionTokens());
            result.put("totalTokens", s.getTotalTokens() == null ? 0 : s.getTotalTokens());
            result.put("cacheTokens", s.getCacheTokens() == null ? 0 : s.getCacheTokens());
            return result;
        }).defaultIfEmpty(Map.of("totalCalls", 0L, "cacheTokens", 0L, "promptTokens",
                0L, "completionTokens", 0L, "totalTokens", 0L));
    }

    /**
     * 按 Key 分页查询调用明细（明细表流水，监控明细弹窗用）
     */
    public Mono<Page<ApiKeyUsage>> list(ApiKeyUsageRequest request, Pageable pageable) {
        ParameterSql parameterSql = request.buildWhereSql();
        // 查询当前页数据
        String searchSql = "SELECT * FROM api_key_usage" + parameterSql.whereSql() + ContextUtil.applyPage(pageable);
        Mono<List<ApiKeyUsage>> searchMono = this.queryWith(searchSql, parameterSql.params(),
                ApiKeyUsage.class).collectList();
        // 查询总数
        String countSql = "SELECT COUNT(*) FROM api_key_usage" + parameterSql.whereSql();
        Mono<Long> countMono = this.countWith(countSql, parameterSql.params()).defaultIfEmpty(0L);

        return Mono.zip(searchMono, countMono).map(tuple2 ->
                new PageImpl<>(tuple2.getT1(), pageable, tuple2.getT2()));

    }

    /**
     * API Key 管理列表分页：api_key LEFT JOIN 汇总表，按创建时间降序，
     * 每行直接带出累计用量（避免前端逐行再调 usage-summary 造成 N+1）。
     */
    public Mono<Page<UsageOverviewRow>> apiKeysPage(Pageable pageable) {
        String sql = """
                select * from (SELECT k.id AS id, k.name AS name, k.key_prefix AS key_prefix, k.tenant_code AS tenant_code,
                 k.system_type AS system_type, k.enabled AS enabled, COALESCE(s.total_calls, 0) AS total_calls,
                 COALESCE(s.prompt_tokens, 0) AS prompt_tokens, COALESCE(s.completion_tokens, 0) AS completion_tokens,
                 COALESCE(s.total_tokens, 0) AS total_tokens, COALESCE(s.cache_tokens, 0) AS cache_tokens, COALESCE(s.input_cost, 0) AS input_cost, COALESCE(s.output_cost, 0) AS output_cost, k.quota AS quota, k.used_quota AS used_quota, s.last_used_at AS last_used_at, k.created_time AS created_time
                 FROM api_key k LEFT JOIN api_key_usage_summary s ON s.api_key_id = k.id WHERE k.is_delete = 0 order by COALESCE(s.total_tokens, 0) desc, id) t
                """ + ContextUtil.applyPage(pageable);
        String countSql = "select count(*) from (SELECT k.* FROM api_key k LEFT JOIN api_key_usage_summary s ON s.api_key_id = k.id WHERE k.is_delete = 0) t";
        Mono<Long> totalMono = super.countWith(countSql, Map.of()).defaultIfEmpty(0L);
        Mono<List<UsageOverviewRow>> listMono = super.queryWith(sql, Map.of(), UsageOverviewRow.class).collectList();
        return Mono.zip(listMono, totalMono).map(tuple2 ->
                new PageImpl<>(tuple2.getT1(), pageable, tuple2.getT2()));
    }

    /**
     * 手动重建汇总表：清空后从明细表全量聚合。
     * 用于升级后把历史用量灌进汇总表，或数据不一致时修复。
     */
    public Mono<Long> rebuildSummary() {
        log.debug("手动触发用量汇总表重建...");
        return this.summaryRepository.truncate()
                .then(this.summaryRepository.rebuildFromDetail())
                .flatMap(n -> this.summaryRepository.syncUsedQuota().thenReturn(n))
                .doOnSuccess(n -> log.debug("用量汇总表重建完成，共 {} 个 Key", n));
    }

    /**
     * 全量对话用量汇总：所有 Key 对话的总调用/总token/总花费。
     */
    public Mono<ChatUsageSummaryRow> chatUsageSummary() {
        String sql = """
                SELECT COUNT(*) AS total_calls, COALESCE(SUM(s.input_tokens), 0) AS prompt_tokens,
                 COALESCE(SUM(s.output_tokens), 0) AS completion_tokens, COALESCE(SUM(s.total_tokens), 0) AS total_tokens,
                 COALESCE(SUM(s.input_cost), 0) AS input_cost, COALESCE(SUM(s.output_cost), 0) AS output_cost
                 FROM chat_session_usage s
                """;
        return super.queryWith(sql, Map.of(), ChatUsageSummaryRow.class).next().map(row -> {
            double inputCost = row.getInputCost() == null ? 0d : row.getInputCost();
            double outputCost = row.getOutputCost() == null ? 0d : row.getOutputCost();
            row.setTotalCost(inputCost + outputCost);
            return row;
        }).defaultIfEmpty(new ChatUsageSummaryRow(0L, 0L, 0L, 0L, 0d, 0d, 0d));
    }

    /**
     * 平台对话会话用量分页：每个会话一行（按会话聚合的总消耗）。
     */
    public Mono<Page<ChatUsageRow>> chatUsageList(Pageable pageable) {
        String sql = "SELECT s.id AS id, s.session_id AS session_id, s.user_id AS user_id, s.title AS title," +
                " s.chat_count AS chat_count, s.input_tokens AS input_tokens, s.output_tokens AS output_tokens," +
                " s.reasoning_tokens AS reasoning_tokens, s.cache_tokens AS cache_tokens, s.total_tokens AS total_tokens," +
                " s.input_cost AS input_cost, s.output_cost AS output_cost, s.total_cost AS total_cost, s.updated_time AS updated_time" +
                " FROM chat_session_usage s ORDER BY s.updated_time DESC, s.id DESC" + ContextUtil.applyPage(pageable);
        String countSql = "SELECT COUNT(*) FROM chat_session_usage s";
        Mono<List<ChatUsageRow>> listMono = super.queryWith(sql, Map.of(), ChatUsageRow.class).collectList();
        Mono<Long> countMono = super.countWith(countSql, Map.of()).defaultIfEmpty(0L);
        return Mono.zip(listMono, countMono).map(t -> new PageImpl<>(t.getT1(), pageable, t.getT2()));
    }

    /**
     * 用量监控总览：直接查汇总表 JOIN api_key，按 total_tokens 降序分页。
     * <p>
     * 返回：全局合计卡片数字 + 当前页数据行 + 分页信息（totalElements/totalPages/number/size）。
     *
     * @param pageable 分页参数（默认 size=10，由 Controller 设定）
     */
    public Mono<Map<String, Object>> overview(Pageable pageable) {
        String countQuery = "select sum(if(is_delete = 0, 1, 0)) as active_keys, sum(if(is_delete = 1, 1, 0)) as deleted_keys from api_key";
        Mono<List<ApiKeyUsageCount>> collectedList = super.queryWith(countQuery, Map.of(), ApiKeyUsageCount.class).collectList();
        Mono<UsageOverviewRow> totalsMono = this.summaryRepository.globalTotals()
                .defaultIfEmpty(new UsageOverviewRow(null, null, null, null,
                        null, null, null, 0L, 0L, 0L, 0L, 0L,
                        null, null, null, null, null, null));
        String sql = """
                select * from (SELECT k.id AS id, k.name AS name, k.key_prefix AS key_prefix,
                 k.tenant_code AS tenant_code, k.system_type AS system_type, k.enabled AS enabled, k.is_delete AS is_delete,
                 COALESCE(s.total_calls, 0) AS total_calls, COALESCE(s.prompt_tokens, 0) AS prompt_tokens,
                 COALESCE(s.completion_tokens, 0) AS completion_tokens, COALESCE(s.total_tokens, 0) AS total_tokens,
                 COALESCE(s.input_cost, 0) AS input_cost, COALESCE(s.output_cost, 0) AS output_cost,
                 COALESCE(s.cache_tokens, 0)  AS cache_tokens,
                 s.last_used_at AS last_used_at FROM api_key k LEFT JOIN api_key_usage_summary s ON
                 s.api_key_id = k.id order by COALESCE(s.total_tokens, 0) desc, id) t
                """ + ContextUtil.applyPage(pageable);
        Mono<List<UsageOverviewRow>> listMono = super.queryWith(sql, Map.of(), UsageOverviewRow.class).collectList();
        return Mono.zip(collectedList, totalsMono).flatMap(t -> {
            AtomicLong totalKeys = new AtomicLong(0);
            List<ApiKeyUsageCount> activeKeys = t.getT1();
            UsageOverviewRow g = t.getT2();
            return listMono.map(list -> {
                Map<String, Object> result = new LinkedHashMap<>();
                // 顶部统计卡片
                if (!ObjectUtils.isEmpty(activeKeys)) {
                    ApiKeyUsageCount first = activeKeys.getFirst();
                    if (ObjectUtils.isEmpty(first.getActiveKeys())) {
                        first.setActiveKeys(0L);
                    }
                    if (ObjectUtils.isEmpty(first.getDeletedKeys())) {
                        first.setDeletedKeys(0L);
                    }
                    totalKeys.set(first.getActiveKeys() + first.getDeletedKeys());
                    result.put("totalKeys", totalKeys.get());
                    result.put("activeKeys", first.getActiveKeys());
                    result.put("deletedKeys", first.getDeletedKeys());
                } else {
                    result.put("totalKeys", 0);
                    result.put("activeKeys", 0);
                    result.put("deletedKeys", 0);
                }
                result.put("totalCalls", g.totalCalls() == null ? 0L : g.totalCalls());
                result.put("promptTokens", g.promptTokens() == null ? 0L : g.promptTokens());
                result.put("completionTokens", g.completionTokens() == null ? 0L : g.completionTokens());
                result.put("totalTokens", g.totalTokens() == null ? 0L : g.totalTokens());
                double v = g.inputCost() == null ? 0d : g.inputCost();
                result.put("inputCost", v);
                double v1 = g.outputCost() == null ? 0d : g.outputCost();
                result.put("outputCost", v1);
                result.put("cacheTokens", g.cacheTokens());
                result.put("totalCost", v + v1);
                // 当前页数据
                result.put("content", list);
                // 分页信息
                result.put("number", pageable.getPageNumber());
                result.put("size", pageable.getPageSize());
                result.put("totalElements", totalKeys.get());
                int totalPages = pageable.getPageSize() == 0 ? 0
                        : (int) ((totalKeys.get() + pageable.getPageSize() - 1) / pageable.getPageSize());
                result.put("totalPages", totalPages);
                return result;
            });
        });
    }


}
