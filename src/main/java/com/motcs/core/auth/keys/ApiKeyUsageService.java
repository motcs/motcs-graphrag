package com.motcs.core.auth.keys;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API Key 使用监控服务：记录每次 Key 对话的 token 消耗，提供总量/明细查询
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyUsageService {

    private final ApiKeyUsageRepository apiKeyUsageRepository;

    /**
     * 记录一次 API Key 对话的 token 消耗（流结束后异步调用，不阻塞响应）
     */
    public Mono<Void> record(Long apiKeyId, String userId, String sessionId, String model,
                             Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        if (apiKeyId == null || totalTokens == null || totalTokens <= 0) {
            // token 缺失（如异常中断未拿到 usage）时不落库，避免脏数据
            return Mono.empty();
        }
        ApiKeyUsage usage = ApiKeyUsage.builder()
                .apiKeyId(apiKeyId)
                .userId(userId == null ? "" : userId)
                .sessionId(sessionId == null ? "" : sessionId)
                .model(model == null ? "" : model)
                .promptTokens(promptTokens == null ? 0 : promptTokens)
                .completionTokens(completionTokens == null ? 0 : completionTokens)
                .totalTokens(totalTokens)
                .createdTime(LocalDateTime.now())
                .build();
        return apiKeyUsageRepository.save(usage).doOnSuccess(u -> {
            if (u != null) {
                log.info("API Key 用量已记录: apiKeyId={}, userId={}, totalTokens={}", apiKeyId, userId, totalTokens);
            }
        }).then();
    }

    /**
     * 按 Key 汇总使用情况：调用次数 + 总 token 消耗
     */
    public Mono<Map<String, Object>> summary(Long apiKeyId) {
        Mono<Long> countMono = apiKeyUsageRepository.countByApiKeyId(apiKeyId).defaultIfEmpty(0L);
        Mono<Map<String, Object>> sumMono = apiKeyUsageRepository.findByApiKeyIdOrderByCreatedTimeDesc(apiKeyId)
                .collect(() -> new HashMap<>() {{
                    put("promptTokens", 0);
                    put("completionTokens", 0);
                    put("totalTokens", 0);
                }}, (acc, u) -> {
                    acc.put("promptTokens", (Integer) acc.get("promptTokens") + nullToZero(u.getPromptTokens()));
                    acc.put("completionTokens", (Integer) acc.get("completionTokens") + nullToZero(u.getCompletionTokens()));
                    acc.put("totalTokens", (Integer) acc.get("totalTokens") + nullToZero(u.getTotalTokens()));
                });
        return Mono.zip(countMono, sumMono).map(t -> {
            Map<String, Object> result = new HashMap<>();
            result.put("totalCalls", t.getT1());
            result.putAll(t.getT2());
            return result;
        });
    }

    /**
     * 按 Key 分页查询调用明细
     */
    public Flux<ApiKeyUsage> list(Long apiKeyId, Pageable pageable) {
        return apiKeyUsageRepository.findByApiKeyIdOrderByCreatedTimeDesc(apiKeyId, pageable);
    }

    /**
     * 用量监控总览：所有 Key 的用量汇总 + 全局合计
     * <p>一次全量拉取后在内存分组聚合（Key 与用量规模有限，避免 N+1 与复杂聚合 SQL）</p>
     *
     * @param keys 全部 API Key（含掩码/备注/租户/系统/启用状态）
     */
    public Mono<Map<String, Object>> overview(Flux<ApiKey> keys) {
        Mono<List<ApiKey>> keysMono = keys.collectList().defaultIfEmpty(List.of());
        Mono<List<ApiKeyUsage>> usageMono = apiKeyUsageRepository.findAll().collectList().defaultIfEmpty(List.of());
        return Mono.zip(keysMono, usageMono).map(t -> {
            // 按 apiKeyId 聚合用量
            Map<Long, int[]> agg = new HashMap<>();      // calls, prompt, completion, total
            Map<Long, LocalDateTime> lastUsed = new HashMap<>();
            for (ApiKeyUsage u : t.getT2()) {
                Long kid = u.getApiKeyId();
                if (kid == null) {
                    continue;
                }
                int[] a = agg.computeIfAbsent(kid, k -> new int[4]);
                a[0]++;
                a[1] += nullToZero(u.getPromptTokens());
                a[2] += nullToZero(u.getCompletionTokens());
                a[3] += nullToZero(u.getTotalTokens());
                LocalDateTime ct = u.getCreatedTime();
                LocalDateTime prev = lastUsed.get(kid);
                if (ct != null && (prev == null || ct.isAfter(prev))) {
                    lastUsed.put(kid, ct);
                }
            }
            List<Map<String, Object>> perKey = new ArrayList<>();
            long totalCalls = 0, prompt = 0, completion = 0, total = 0;
            for (ApiKey k : t.getT1()) {
                Long kid = k.getId();
                int[] a = agg.getOrDefault(kid, new int[4]);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", kid);
                row.put("prefix", k.getKeyPrefix());
                row.put("name", k.getName());
                row.put("tenantCode", k.getTenantCode());
                row.put("systemType", k.getSystemType());
                row.put("enabled", k.getEnabled());
                row.put("totalCalls", a[0]);
                row.put("promptTokens", a[1]);
                row.put("completionTokens", a[2]);
                row.put("totalTokens", a[3]);
                row.put("lastUsedAt", lastUsed.get(kid));
                perKey.add(row);
                totalCalls += a[0];
                prompt += a[1];
                completion += a[2];
                total += a[3];
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("totalKeys", t.getT1().size());
            result.put("totalCalls", totalCalls);
            result.put("promptTokens", prompt);
            result.put("completionTokens", completion);
            result.put("totalTokens", total);
            result.put("keys", perKey);
            return result;
        });
    }

    private static int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }
}
