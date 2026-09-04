package com.motcs.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 认证与 API Key 管理接口
 *  - POST /api/auth/login    超管登录（账号密码来自配置 app.auth.*）
 *  - POST /api/auth/logout   退出登录
 *  - GET  /api/auth/me       当前登录状态
 *  - GET  /api/auth/api-keys 已生成的 Key 列表（仅掩码）
 *  - POST /api/auth/api-keys 生成新 Key（返回明文一次）
 *  - DELETE /api/auth/api-keys/{id} 删除 Key
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SecurityConfig securityConfig;
    private final ServerSecurityContextRepository securityContextRepository;
    private final ApiKeyService apiKeyService;

    public AuthController(SecurityConfig securityConfig,
                          ServerSecurityContextRepository securityContextRepository,
                          ApiKeyService apiKeyService) {
        this.securityConfig = securityConfig;
        this.securityContextRepository = securityContextRepository;
        this.apiKeyService = apiKeyService;
    }

    /** 超管登录：校验配置账号密码，成功后写入 WebSession 实现会话免登录 */
    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> body,
                                                           ServerWebExchange exchange) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");
        if (securityConfig.getAdminUsername().equals(username)
                && securityConfig.getAdminPassword().equals(password)) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            return securityContextRepository.save(exchange, context)
                    .thenReturn(ResponseEntity.ok(Map.of("success", true, "username", username)));
        }
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("success", false, "message", "用户名或密码错误")));
    }

    /** 退出登录：使 WebSession 失效 */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return exchange.getSession().flatMap(session -> {
            session.invalidate();
            return Mono.just(ResponseEntity.ok().build());
        });
    }

    /** 当前登录状态（前端页面加载时探测） */
    @GetMapping("/me")
    public Mono<ResponseEntity<Map<String, Object>>> me(@AuthenticationPrincipal Object principal) {
        if (principal == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("authenticated", false)));
        }
        return Mono.just(ResponseEntity.ok(Map.of(
                "authenticated", true,
                "username", String.valueOf(principal))));
    }

    /** Key 列表（仅掩码，不含哈希与明文） */
    @GetMapping("/api-keys")
    public Flux<ApiKey> listApiKeys() {
        return apiKeyService.list();
    }

    /** 生成新 Key（明文仅此一次返回；备注必填，为空返回 400） */
    @PostMapping("/api-keys")
    public Mono<ResponseEntity<?>> createApiKey(@RequestBody Map<String, String> body,
                                                @AuthenticationPrincipal Object principal) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "备注（name）必填")));
        }
        return apiKeyService.generate(name, principal == null ? "admin" : String.valueOf(principal))
                .map(ResponseEntity::ok);
    }

    /** 删除 Key（撤销后携带该 Key 的请求立即失效） */
    @DeleteMapping("/api-keys/{id}")
    public Mono<ResponseEntity<Void>> deleteApiKey(@PathVariable Long id) {
        return apiKeyService.delete(id).thenReturn(ResponseEntity.ok().build());
    }
}
