package com.motcs.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * API Key 服务：生成（OpenAI 风格）、鉴权校验、列表、删除。
 * 设计要点：
 * - Key 形如 sk-xxxxxxxx...（前缀 + 40 位随机，去掉易混淆字符 0O1lI）
 * - 数据库只存 SHA-256 哈希与前缀掩码，明文只在生成时返回一次
 * - 鉴权时对请求携带的 Key 做同样哈希后比对
 */
@Service
public class ApiKeyService {

    @Value("${app.auth.api.key.length:40}")
    private Integer apiKeyLen;

    private static final String PREFIX = "sk-";
    private static final char[] ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * 生成一个新 Key，返回明文（仅此一次）
     */
    public Mono<ApiKeyInfo> generate(String name, String createdBy) {
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("备注（name）必填"));
        }
        String plainKey = PREFIX + randomString();
        ApiKey entity = ApiKey.builder()
                .name(name)
                .keyPrefix(prefixMask(plainKey))
                .keyHash(sha256(plainKey))
                .enabled(true)
                .createdBy(createdBy == null || createdBy.isBlank() ? "admin" : createdBy)
                .createdTime(LocalDateTime.now())
                .build();
        return apiKeyRepository.save(entity)
                .map(saved -> ApiKeyInfo.of(saved, plainKey));
    }

    /**
     * 校验请求携带的 Key 是否有效（存在且启用）
     */
    public Mono<Boolean> validate(String key) {
        if (key == null || key.isBlank()) {
            return Mono.just(false);
        }
        return apiKeyRepository.findEnabledByKeyHash(sha256(key.trim()))
                .map(k -> Boolean.TRUE.equals(k.getEnabled()))
                .defaultIfEmpty(false);
    }

    /**
     * 全部 Key（列表展示，不含哈希与明文）
     */
    public Flux<ApiKey> list() {
        return apiKeyRepository.findAll();
    }

    public Mono<Void> delete(Long id) {
        return apiKeyRepository.deleteById(id);
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

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
