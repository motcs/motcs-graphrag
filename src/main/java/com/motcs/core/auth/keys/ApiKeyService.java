package com.motcs.core.auth.keys;

import com.motcs.commons.utils.Utils;
import com.motcs.core.auth.keys.usage.ApiKeyUsageRepository;
import com.motcs.core.auth.keys.usage.quota.ApiKeyQuotaLog;
import com.motcs.core.auth.keys.usage.quota.ApiKeyQuotaLogRepository;
import com.motcs.core.auth.keys.usage.summary.ApiKeyUsageSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * API Key 服务：生成（OpenAI 风格）、鉴权校验、列表、删除。
 * 设计要点：
 * - Key 形如 sk-...（前缀 + 40 位随机，去掉易混淆字符 0O1lI）
 * - 数据库只存 SHA-256 哈希与前缀掩码，明文只在生成时返回一次
 * - 鉴权时对请求携带的 Key 做同样哈希后比对
 * - resolveApiKey 走 Redis 缓存，避免每次查库
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "sk-";
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CACHE_KEY_PREFIX = "motcs:apikey:";
    private static final Duration CACHE_TTL = Duration.ofHours(2);

    private final ApiKeyRepository apiKeyRepository;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ApiKeyUsageRepository apiKeyUsageRepository;
    private final ApiKeyUsageSummaryRepository apiKeyUsageSummaryRepository;
    private final ApiKeyQuotaLogRepository apiKeyQuotaLogRepository;
    @Value("${app.auth.api.key.length:40}")
    private Integer apiKeyLen;

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 从请求头解析并校验 API Key（与 SecurityConfiguration.extractApiKey 同一规则）：
     * Authorization: Bearer sk-... 或 X-API-Key: sk-...；无效/缺失返回 empty
     * 走 Redis 缓存：key = motcs:apikey:{sha256}，TTL 2 小时
     */
    public Mono<ApiKey> resolveApiKey(ServerWebExchange exchange) {
        String key = Utils.extractApiKey(exchange);
        if (ObjectUtils.isEmpty(key)) {
            return Mono.empty();
        }
        String hash = sha256(key.trim());
        String cacheKey = CACHE_KEY_PREFIX + hash;
        Mono<ApiKey> alternate = this.apiKeyRepository.findEnabledByKeyHash(hash)
                .switchIfEmpty(Mono.empty()).filter(ApiKey::getEnabled)
                .flatMap(apiKey -> this.redisTemplate.opsForValue()
                        .set(cacheKey, apiKey, CACHE_TTL).thenReturn(apiKey));
        return this.redisTemplate.opsForValue().get(cacheKey)
                .cast(ApiKey.class).switchIfEmpty(alternate);
    }

    /**
     * 设置新额度（SET）：历史已用清零重算；quota 不能小于 -1，负数只允许 -1
     */
    public Mono<ApiKey> updateQuota(Long id, Double quota, String remark) {
        if (quota == null || quota < -1) {
            return Mono.error(new IllegalArgumentException("额度不能小于 -1，负数只能是 -1（无限制）"));
        }
        return this.apiKeyRepository.findById(id).flatMap(existing -> {
            existing.setQuota(quota);
            existing.setUsedQuota(0.0);
            String cacheKey = CACHE_KEY_PREFIX + existing.getKeyHash();
            ApiKeyQuotaLog log = ApiKeyQuotaLog.builder()
                    .apiKeyId(id).type("SET").amount(quota).balanceAfter(quota).usedAfter(0.0)
                    .remark(remark).createdTime(LocalDateTime.now()).build();
            return this.redisTemplate.delete(cacheKey)
                    .then(this.apiKeyRepository.save(existing))
                    .flatMap(saved -> this.apiKeyQuotaLogRepository.save(log).thenReturn(saved));
        });
    }

    /**
     * 追加额度（ADD）：在现有总额度上累加 amount，已用不变；amount 必须 > 0
     */
    public Mono<ApiKey> appendQuota(Long id, Double amount, String remark) {
        if (amount == null || amount <= 0) {
            return Mono.error(new IllegalArgumentException("追加额度必须大于 0"));
        }
        return this.apiKeyRepository.findById(id).flatMap(existing -> {
            double oldQuota = existing.getQuota() == null ? -1.0 : existing.getQuota();
            // 无限制(-1) 不能追加，先设置一个实际额度
            if (oldQuota < 0) {
                return Mono.error(new IllegalArgumentException("当前为无限制额度，请先设置实际额度再追加"));
            }
            double balance = oldQuota + amount;
            double used = existing.getUsedQuota() == null ? 0 : existing.getUsedQuota();
            existing.setQuota(balance);
            String cacheKey = CACHE_KEY_PREFIX + existing.getKeyHash();
            ApiKeyQuotaLog log = ApiKeyQuotaLog.builder()
                    .apiKeyId(id).type("ADD").amount(amount).balanceAfter(balance).usedAfter(used)
                    .remark(remark).createdTime(LocalDateTime.now()).build();
            return this.redisTemplate.delete(cacheKey)
                    .then(this.apiKeyRepository.save(existing))
                    .flatMap(saved -> this.apiKeyQuotaLogRepository.save(log).thenReturn(saved));
        });
    }

    /**
     * 判断 Key 是否还有费用额度：quota=-1 无限制；quota=0 或已用>=额度 拒绝请求
     */
    public boolean hasQuota(ApiKey apiKey) {
        if (apiKey == null) {
            return false;
        }
        double quota = apiKey.getQuota() == null ? -1.0 : apiKey.getQuota();
        if (quota < 0) {
            return true;
        }
        double used = apiKey.getUsedQuota() == null ? 0 : apiKey.getUsedQuota();
        return used < quota;
    }

    /**
     * 本次对话结束后累加已用额度
     */
    public Mono<Void> consumeQuota(Long apiKeyId, double cost) {
        if (apiKeyId == null || cost <= 0) {
            return Mono.empty();
        }
        return this.apiKeyRepository.addUsedQuota(apiKeyId, cost).then();
    }

    /**
     * 生成一个新 Key，返回明文（仅此一次）
     * 租户编码与系统类型必填：对话/上传文档时以此为归属，区分租户自定义内容
     */
    public Mono<ApiKeyRecord> generate(String name, String tenantCode, String systemType, String createdBy, Double quota) {
        String plainKey = PREFIX + randomString();
        ApiKey entity = ApiKey.builder().name(name).keyPrefix(prefixMask(plainKey))
                .keyHash(sha256(plainKey)).tenantCode(tenantCode.trim())
                .systemType(systemType.trim()).enabled(true)
                .quota(quota == null ? -1.0 : quota).usedQuota(0.0)
                .createdBy(ObjectUtils.isEmpty(createdBy) ? "xxhzj" : createdBy)
                .createdTime(LocalDateTime.now()).build();
        return this.apiKeyRepository.save(entity).flatMap(saved -> {
            double q = saved.getQuota() == null ? -1.0 : saved.getQuota();
            if (q > 0) {
                ApiKeyQuotaLog log = ApiKeyQuotaLog.builder()
                        .apiKeyId(saved.getId()).type("SET").amount(q).balanceAfter(q).usedAfter(0.0)
                        .remark("初始化额度").createdTime(LocalDateTime.now()).build();
                return this.apiKeyQuotaLogRepository.save(log).thenReturn(saved);
            }
            return Mono.just(saved);
        }).map(saved -> ApiKeyRecord.of(saved, plainKey));
    }

    /**
     * 删除 Key：同时清理 Redis 缓存
     */
    public Mono<Void> delete(Long id) {
        return this.apiKeyRepository.findById(id).flatMap(apiKey -> {
            String cacheKey = CACHE_KEY_PREFIX + apiKey.getKeyHash();
            // 软删除：置 isDelete=true（Key 失效、管理列表不可见），用量流水与统计保留
            apiKey.setIsDelete(Boolean.TRUE);
            return this.redisTemplate.delete(cacheKey)
                    .then(this.apiKeyRepository.save(apiKey));
        }).then();
    }

    /**
     * 启用/停用 Key：关闭后该 Key 临时失效，同时清理缓存；重新开启后立即恢复（重新查库缓存）
     */
    public Mono<ApiKey> setEnabled(Long id) {
        if (ObjectUtils.isEmpty(id)) {
            return Mono.empty();
        }
        return this.apiKeyRepository.findById(id).flatMap(existing -> {
            existing.setEnabled(!existing.getEnabled());
            // 状态变更时清理缓存
            String cacheKey = CACHE_KEY_PREFIX + existing.getKeyHash();
            return this.redisTemplate.delete(cacheKey)
                    .then(this.apiKeyRepository.save(existing));
        });
    }

    private String randomString() {
        StringBuilder sb = new StringBuilder(apiKeyLen);
        for (int i = 0; i < apiKeyLen; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 展示掩码：sk-Ab3Xy7...（前缀 + 前 8 位）
     */
    private String prefixMask(String key) {
        int len = Math.min(PREFIX.length() + 8, key.length());
        return key.substring(0, len) + "...";
    }

}
