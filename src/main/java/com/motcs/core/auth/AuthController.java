package com.motcs.core.auth;

import com.motcs.commons.annotation.RestServerException;
import com.motcs.config.SecurityConfiguration;
import com.motcs.core.auth.keys.ApiKey;
import com.motcs.core.auth.keys.ApiKeyService;
import com.motcs.core.auth.token.AuthenticationToken;
import com.motcs.core.auth.token.TokenStore;
import com.motcs.core.request.ApiKeyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 认证与 API Key 管理接口
 * - POST /api/auth/login    超管登录（账号密码来自配置 app.auth.*）
 * - POST /api/auth/logout   退出登录
 * - GET  /api/auth/me       当前登录状态
 * - GET  /api/auth/api-keys 已生成的 Key 列表（仅掩码）
 * - POST /api/auth/api-keys 生成新 Key（返回明文一次）
 * - DELETE /api/auth/api-keys/{id} 删除 Key
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenStore tokenStore;
    private final ApiKeyService apiKeyService;

    /**
     * 超管登录（HTTP Basic Auth）：POST /api/auth/login 携带
     * Authorization: Basic base64(username:password)，由 Spring Security Basic 认证
     * 过滤链完成校验，认证通过后进入本方法：以 WebSession id 作为 x-token，
     * 登记到 TokenStore（带过期时间，跟随会话空闲超时）并返回给前端；
     * 后续请求携带请求头 x-token 由 Token 过滤器校验即视为已登录。
     * 密码错误由 SecurityConfiguration 的失败处理器返回 JSON 401。
     */
    @PostMapping("/login")
    public Mono<AuthenticationToken> login(ServerWebExchange exchange, Authentication authentication) {
        // 仅当请求未携带 Basic 凭据仍打到本方法时兜底（如空凭据请求），明确返回 401
        if (authentication == null || !authentication.isAuthenticated()) {
            return Mono.error(RestServerException.withMsg("用户名或密码错误"));
        }
        return exchange.getSession().flatMap(session -> {
            // 按现有处理方式：以 WebSession id 作为 token 登记到 TokenStore，
            // 过期时间默认 2 小时（可配置 app.auth.token.ttl-seconds），每次使用自动续期
            long ttlSeconds = tokenStore.getDefaultTtlSeconds();
            tokenStore.put(session.getId(), authentication.getName());
            // 显式下发 CSRF cookie（双提交模式）：前端读取 XSRF-TOKEN cookie 值放入
            // X-CSRF-TOKEN 请求头，后端比对 cookie 与 header。
            // 不依赖 CSRF 过滤器的异步生成时序，保证登录响应必定携带 cookie。
            String csrfValue = UUID.randomUUID().toString();
            // 持久化 30 天（非会话级 cookie）：避免浏览器重启后 cookie 丢失导致 POST 缺 CSRF 头；
            // 此后 CSRF 过滤器只读不写，cookie 值始终保持登录时的值，直到重新登录更新
            exchange.getResponse().addCookie(ResponseCookie.from("XSRF-TOKEN", csrfValue)
                    .path("/").httpOnly(false).sameSite("Lax").maxAge(java.time.Duration.ofDays(30)).build());
            return Mono.just(AuthenticationToken.of(
                    session.getId(), ttlSeconds, Instant.now().getEpochSecond()));
        });
    }

    /**
     * 退出登录：注销 x-token（从请求头读取）
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst(SecurityConfiguration.X_TOKEN);
        tokenStore.remove(token);
        return Mono.just(ResponseEntity.ok().build());
    }

    /**
     * 当前登录状态（前端页面加载时探测）
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(@AuthenticationPrincipal Object principal) {
        if (ObjectUtils.isEmpty(principal)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("authenticated", false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of("authenticated",
                true, "username", String.valueOf(principal))));
    }

    /**
     * Key 列表（仅掩码，不含哈希与明文）
     */
    @GetMapping("/api-keys")
    public Flux<ApiKey> listApiKeys() {
        return apiKeyService.list();
    }

    /**
     * 生成新 Key（明文仅此一次返回；备注/租户编码/系统类型必填，为空返回 400）
     */
    @PostMapping("/api-keys")
    public Mono<ResponseEntity<?>> createApiKey(@RequestBody ApiKeyRequest request,
                                                @AuthenticationPrincipal Object principal) {
        String name = ObjectUtils.isEmpty(request.getName()) ? request.getName().trim() : "";
        String tenantCode = ObjectUtils.isEmpty(request.getTenantCode()) ? request.getTenantCode().trim() : "";
        String systemType = ObjectUtils.isEmpty(request.getSystemType()) ? request.getSystemType().trim() : "";
        if (name.isEmpty() || tenantCode.isEmpty() || systemType.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "备注（name）、租户编码（tenantCode）、系统类型（systemType）均必填")));
        }
        if ("0".equals(tenantCode)) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", "API Key 不允许绑定租户 0（超管全局租户），请填写具体租户编码")));
        }
        String createdBy = ObjectUtils.isEmpty(principal) ? "xxhzj" : String.valueOf(principal);
        return apiKeyService.generate(name, tenantCode, systemType, createdBy)
                .map(ResponseEntity::ok);
    }

    /**
     * 删除 Key（撤销后携带该 Key 的请求立即失效）
     */
    @DeleteMapping("/api-keys/{id}")
    public Mono<ResponseEntity<Void>> deleteApiKey(@PathVariable Long id) {
        return apiKeyService.delete(id).thenReturn(ResponseEntity.ok().build());
    }

}
