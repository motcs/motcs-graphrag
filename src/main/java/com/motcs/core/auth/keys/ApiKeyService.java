package com.motcs.core.auth.keys;

import com.motcs.commons.utils.Utils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import org.springframework.web.server.ServerWebExchange;
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
 * - Key 形如 sk-...（前缀 + 40 位随机，去掉易混淆字符 0O1lI）
 * - 数据库只存 SHA-256 哈希与前缀掩码，明文只在生成时返回一次
 * - 鉴权时对请求携带的 Key 做同样哈希后比对
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
    private final ApiKeyRepository apiKeyRepository;
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
     * 查询启用状态的 Key 实体（认证过滤用：把实体放入认证信息，供对话归属记录/校验）
     */
    public Mono<ApiKey> resolveApiKey(ServerWebExchange exchange) {
        String key = Utils.extractApiKey(exchange);
        if (key == null || key.isBlank()) {
            return Mono.empty();
        }
        return this.apiKeyRepository.findEnabledByKeyHash(sha256(key.trim()))
                .filter(k -> Boolean.TRUE.equals(k.getEnabled()));
    }

    /**
     * 生成一个新 Key，返回明文（仅此一次）
     * 租户编码与系统类型必填：对话/上传文档时以此为归属，区分租户自定义内容
     */
    public Mono<ApiKeyRecord> generate(String name, String tenantCode, String systemType, String createdBy) {
        if (ObjectUtils.isEmpty(name)) {
            return Mono.error(new IllegalArgumentException("备注（name）必填"));
        }
        if (ObjectUtils.isEmpty(tenantCode)) {
            return Mono.error(new IllegalArgumentException("租户编码（tenantCode）必填"));
        }
        if (ObjectUtils.isEmpty(systemType)) {
            return Mono.error(new IllegalArgumentException("系统类型（systemType）必填"));
        }
        if ("0".equals(tenantCode.trim())) {
            return Mono.error(new IllegalArgumentException("API Key 不允许绑定租户 0（超管全局租户），请填写具体租户编码"));
        }
        String plainKey = PREFIX + randomString();
        ApiKey entity = ApiKey.builder().name(name).keyPrefix(prefixMask(plainKey))
                .keyHash(sha256(plainKey)).tenantCode(tenantCode.trim())
                .systemType(systemType.trim()).enabled(true)
                .createdBy(ObjectUtils.isEmpty(createdBy) ? "xxhzj" : createdBy)
                .createdTime(LocalDateTime.now()).build();
        return apiKeyRepository.save(entity)
                .map(saved -> ApiKeyRecord.of(saved, plainKey));
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

}
