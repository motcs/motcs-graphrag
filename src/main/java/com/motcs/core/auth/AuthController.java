package com.motcs.core.auth;

import com.motcs.commons.annotation.RestServerException;
import com.motcs.commons.utils.Utils;
import com.motcs.core.auth.keys.ApiKeyService;
import com.motcs.core.auth.keys.usage.ApiKeyUsage;
import com.motcs.core.auth.keys.usage.ApiKeyUsageRequest;
import com.motcs.core.auth.keys.usage.ApiKeyUsageService;
import com.motcs.core.auth.keys.usage.summary.UsageOverviewRow;
import com.motcs.core.auth.token.AuthenticationToken;
import com.motcs.core.auth.token.TokenStore;
import com.motcs.core.request.ApiKeyRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
@Tag(name = "认证与Key管理接口", description = "认证与 API Key 管理接口")
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/v1")
public class AuthController {

    private final TokenStore tokenStore;
    private final ApiKeyService apiKeyService;
    private final ApiKeyUsageService apiKeyUsageService;

    /**
     * 超管登录（HTTP Basic Auth）：POST /auth/v1/login 携带
     * Authorization: Basic base64(username:password)，由 Spring Security Basic 认证
     * 过滤链完成校验，认证通过后进入本方法：以 WebSession id 作为 x-token，
     * 登记到 TokenStore（带过期时间，跟随会话空闲超时）并返回给前端；
     * 后续请求携带请求头 x-token 由 Token 过滤器校验即视为已登录。
     * 密码错误由 SecurityConfiguration 的失败处理器返回 JSON 401。
     */
    @PostMapping("/login")
    @Operation(summary = "超管登录（HTTP Basic Auth）")
    public Mono<AuthenticationToken> login(ServerWebExchange exchange, Authentication authentication) {
        // 仅当请求未携带 Basic 凭据仍打到本方法时兜底（如空凭据请求），明确返回 401
        if (ObjectUtils.isEmpty(authentication) || !authentication.isAuthenticated()) {
            return Mono.error(RestServerException.withMsg("用户名或密码错误"));
        }
        return exchange.getSession().flatMap(session -> {
            // 按现有处理方式：以 WebSession id 作为 token 登记到 TokenStore，
            // 过期时间默认 2 小时（可配置 app.auth.token.ttl-seconds），每次使用自动续期
            long ttlSeconds = this.tokenStore.getDefaultTtlSeconds();
            this.tokenStore.put(session.getId(), authentication.getName());
            // 显式下发 CSRF cookie（双提交模式）：前端读取 XSRF-TOKEN cookie 值放入
            // X-CSRF-TOKEN 请求头，后端比对 cookie 与 header。
            // 不依赖 CSRF 过滤器的异步生成时序，保证登录响应必定携带 cookie。
            String csrfValue = UUID.randomUUID().toString();
            // 持久化 30 天（非会话级 cookie）：避免浏览器重启后 cookie 丢失导致 POST 缺 CSRF 头；
            // 此后 CSRF 过滤器只读不写，cookie 值始终保持登录时的值，直到重新登录更新
            exchange.getResponse().addCookie(ResponseCookie.from("XSRF-TOKEN", csrfValue)
                    .path("/").httpOnly(false).sameSite("Lax").maxAge(java.time.Duration.ofDays(30)).build());
            return Mono.just(AuthenticationToken.of(session.getId(), ttlSeconds, Instant.now().getEpochSecond()));
        });
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录：注销 x-token（从请求头读取）")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst(Utils.X_TOKEN);
        this.tokenStore.remove(token);
        return Mono.just(ResponseEntity.ok().build());
    }

    @GetMapping("/me")
    @Operation(summary = "当前登录状态（前端页面加载时探测）")
    public Mono<ResponseEntity<Map<String, Object>>> me(@AuthenticationPrincipal Object principal) {
        if (ObjectUtils.isEmpty(principal)) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("authenticated", false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of("authenticated",
                true, "username", String.valueOf(principal))));
    }

    @GetMapping("/api-keys")
    @Operation(summary = "Key 列表分页（含累计用量，按创建时间降序；默认每页10条）")
    public Mono<ResponseEntity<Page<UsageOverviewRow>>> apiKeys(Pageable pageable) {
        return this.apiKeyUsageService.apiKeysPage(pageable).map(ResponseEntity::ok);
    }

    @PostMapping("/api-keys")
    @Operation(summary = " 生成新 Key（明文仅此一次返回；备注/租户编码/系统类型必填，为空返回 400）")
    public Mono<ResponseEntity<?>> createApiKey(@RequestBody ApiKeyRequest request,
                                                @AuthenticationPrincipal Object principal) {
        if (ObjectUtils.isEmpty(request.getName())) {
            Map<String, Object> stringMap = Map.of("success", false, "message", "备注（name）必填");
            return Mono.just(ResponseEntity.badRequest().body(stringMap));
        }
        if (ObjectUtils.isEmpty(request.getTenantCode())) {
            Map<String, Object> stringMap = Map.of("success", false, "message", "租户编码（tenantCode）必填");
            return Mono.just(ResponseEntity.badRequest().body(stringMap));
        }
        if (ObjectUtils.isEmpty(request.getSystemType())) {
            Map<String, Object> stringMap = Map.of("success", false, "message", "系统类型（systemType）必填");
            return Mono.just(ResponseEntity.badRequest().body(stringMap));
        }
        String createdBy = ObjectUtils.isEmpty(principal) ? "xxhzj" : String.valueOf(principal);
        return this.apiKeyService.generate(request.getName(), request.getTenantCode(),
                request.getSystemType(), createdBy).map(ResponseEntity::ok);
    }

    @DeleteMapping("/api-keys/{id}")
    @Operation(summary = "删除 Key（撤销后携带该 Key 的请求立即失效）")
    public Mono<ResponseEntity<Void>> deleteApiKey(@PathVariable Long id) {
        return this.apiKeyService.delete(id).thenReturn(ResponseEntity.ok().build());
    }

    /**
     * 启用/停用 Key：关闭后该 Key 临时失效（携带它的请求立即 401），可随时重新开启
     * Body: {"enabled": true} 或 {"enabled": false}
     */
    @PutMapping("/api-keys/{apiKeyId}/enabled")
    @Operation(summary = "启用/停用 Key（关闭后临时失效，可随时重新开启）")
    public Mono<ResponseEntity<Map<String, Object>>> setApiKeyEnabled(@PathVariable Long apiKeyId) {
        Map<String, Object> objectMap = new HashMap<>();
        return this.apiKeyService.setEnabled(apiKeyId).flatMap(updated -> {
            objectMap.put("success", true);
            objectMap.put("id", updated.getId());
            objectMap.put("enabled", updated.getEnabled());
            return Mono.just(ResponseEntity.ok(objectMap));
        }).switchIfEmpty(Mono.fromCallable(() -> {
            objectMap.put("success", false);
            objectMap.put("message", "API Key 不存在");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(objectMap);
        }));
    }

    /**
     * Key 使用监控汇总：调用次数 + 总 token 消耗（prompt/completion/total）
     */
    @GetMapping("/api-keys/{apiKeyId}/usage-summary")
    @Operation(summary = "Key 使用汇总：调用次数 + 总 token 消耗")
    public Mono<ResponseEntity<Map<String, Object>>> apiKeyUsageSummary(@PathVariable Long apiKeyId) {
        return this.apiKeyUsageService.summary(apiKeyId).map(ResponseEntity::ok);
    }

    /**
     * Key 使用监控明细：按时间倒序分页（标准 Pageable）
     */
    @GetMapping("/api-keys/{apiKeyId}/usage")
    @Operation(summary = "Key 使用明细（每次对话的 token 消耗，分页）")
    public Mono<ResponseEntity<Page<ApiKeyUsage>>> apiKeyUsageList(@PathVariable Long apiKeyId, Pageable pageable) {
        ApiKeyUsageRequest request = new ApiKeyUsageRequest();
        request.setApiKeyId(apiKeyId);
        Mono<Page<ApiKeyUsage>> list = this.apiKeyUsageService.list(request, pageable);
        return list.map(ResponseEntity::ok);
    }

    /**
     * 用量监控总览：查汇总表（按 Key 聚合快照）JOIN api_key，按 total_tokens 降序分页
     * （默认每页10条），顶部统计卡片为全局合计。
     */
    @GetMapping("/usage-overview")
    @Operation(summary = "用量监控总览（分页，按使用量降序，默认每页10条）")
    public Mono<ResponseEntity<Map<String, Object>>> apiKeyUsageOverview(Pageable pageable) {
        return this.apiKeyUsageService.overview(pageable).map(ResponseEntity::ok);
    }

    /**
     * 手动重建用量汇总表：从 api_key_usage 明细表全量聚合到汇总表。
     * 升级后首次使用时调用一次，把历史用量灌进来。
     */
    @PostMapping("/usage-summary/rebuild")
    @Operation(summary = "重建用量汇总表（从明细表聚合历史数据）")
    public Mono<ResponseEntity<Map<String, Object>>> rebuildUsageSummary() {
        return this.apiKeyUsageService.rebuildSummary().map(n -> {
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("message", "重建完成，共 " + n + " 个 Key");
            return ResponseEntity.ok(body);
        }).onErrorResume(e -> {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("message", "重建失败: " + e.getMessage());
            return Mono.just(ResponseEntity.internalServerError().body(body));
        });
    }

}
