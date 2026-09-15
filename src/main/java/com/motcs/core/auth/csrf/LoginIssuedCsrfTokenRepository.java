package com.motcs.core.auth.csrf;

import org.jspecify.annotations.NonNull;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 只读型 CSRF Cookie 仓库：CSRF token 仅在登录接口显式下发（Set-Cookie: XSRF-TOKEN），
 * 校验时从请求 Cookie 读取并与请求头 X-CSRF-TOKEN 比对。
 *
 * <p>与 {@link CookieServerCsrfTokenRepository} 的区别：本实现 {@link #saveToken} 为空操作，
 * 禁止 CSRF 过滤器在后续请求中重新生成/覆盖 cookie，保证 cookie 值始终保持登录时的值
 * （不会因页面刷新、任意 GET/POST 请求而改变）。
 *
 * <p>校验头显式设为 X-CSRF-TOKEN（与前端一致，Spring 默认是 X-XSRF-TOKEN）。
 *
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-09 星期三
 */
public class LoginIssuedCsrfTokenRepository implements ServerCsrfTokenRepository {

    /**
     * 与登录接口下发的 cookie 名保持一致
     */
    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    /**
     * 与前端请求头保持一致
     */
    public static final String CSRF_HEADER_NAME = "X-CSRF-TOKEN";

    private final CookieServerCsrfTokenRepository delegate;

    public LoginIssuedCsrfTokenRepository() {
        this.delegate = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        this.delegate.setCookieName(CSRF_COOKIE_NAME);
        this.delegate.setHeaderName(CSRF_HEADER_NAME);
    }

    @Override
    public @NonNull Mono<CsrfToken> generateToken(@NonNull ServerWebExchange exchange) {
        // 仅用于 CSRF 过滤器在无 cookie 的豁免请求上占位生成；值不会落盘/下发
        return this.delegate.generateToken(exchange);
    }

    @Override
    public @NonNull Mono<Void> saveToken(@NonNull ServerWebExchange exchange, CsrfToken token) {
        // 空操作：CSRF cookie 只由登录接口显式下发，这里不写也不删，
        // 保证 cookie 值始终保持登录时的值，不被过滤器覆盖
        return Mono.empty();
    }

    @Override
    public @NonNull Mono<CsrfToken> loadToken(@NonNull ServerWebExchange exchange) {
        // 从请求 Cookie 中读取 XSRF-TOKEN，供 CSRF 过滤器与请求头 X-CSRF-TOKEN 比对
        return this.delegate.loadToken(exchange);
    }
}
