package com.motcs.core.auth.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录令牌（x-token）存储：登录成功后把令牌（以 WebSession id 作为 token）登记到内存，
 * 后续请求携带 x-token 头由 Token 过滤器经本存储校验并恢复登录态。
 * 令牌带过期时间（默认 2 小时，可配置 app.auth.token.ttl-seconds），
 * **滑动过期**：每次有效使用都会刷新过期时间，持续活跃则不会掉线。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Service
public class TokenStore {

    /**
     * 默认过期时间（秒）：2 小时
     */
    private static final long DEFAULT_TTL_SECONDS = 7200;
    private final Map<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    public TokenStore(@Value("${app.auth.token.ttl-seconds:7200}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS;
    }

    public long getDefaultTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * 登记登录令牌（使用默认过期时间 2 小时），token 由调用方提供（如 WebSession id）
     */
    public void put(String token, String username) {
        put(token, username, ttlSeconds);
    }

    /**
     * 登记登录令牌并指定过期秒数；非正数时回退默认值
     */
    public void put(String token, String username, long ttlSeconds) {
        if (token == null || token.isBlank() || username == null || username.isBlank()) {
            return;
        }
        long ttl = ttlSeconds > 0 ? ttlSeconds : this.ttlSeconds;
        tokens.put(token, new TokenEntry(username, ttl, System.currentTimeMillis() + ttl * 1000));
    }

    /**
     * 按令牌解析用户名；令牌缺失、不存在或已过期返回 empty（过期条目同时清除）。
     * 校验通过时刷新过期时间（滑动过期）。
     */
    public Mono<String> getUsername(String token) {
        if (ObjectUtils.isEmpty(token)) {
            return Mono.empty();
        }
        TokenEntry entry = tokens.get(token);
        if (ObjectUtils.isEmpty(entry)) {
            return Mono.empty();
        }
        if (entry.expired()) {
            tokens.remove(token);
            return Mono.empty();
        }
        // 每次有效使用刷新过期时间
        tokens.put(token, entry.refreshed());
        return Mono.just(entry.username());
    }

    /**
     * 注销令牌（退出登录）
     */
    public void remove(String token) {
        if (!ObjectUtils.isEmpty(token)) {
            tokens.remove(token);
        }
    }

    private record TokenEntry(String username, long ttlSeconds, long expireAtMillis) {
        boolean expired() {
            return System.currentTimeMillis() >= expireAtMillis;
        }

        TokenEntry refreshed() {
            return new TokenEntry(username, ttlSeconds, System.currentTimeMillis() + ttlSeconds * 1000);
        }
    }
}
