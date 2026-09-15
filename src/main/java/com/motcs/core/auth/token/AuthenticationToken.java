package com.motcs.core.auth.token;

import org.springframework.web.server.WebSession;

import java.io.Serializable;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-07 星期一
 */
public record AuthenticationToken(String token, Long expires, Long lastAccessTime) implements Serializable {

    public static AuthenticationToken of(String token, Long expires, Long lastAccessTime) {
        return new AuthenticationToken(token, expires, lastAccessTime);
    }

    public static AuthenticationToken withSession(WebSession session) {
        return AuthenticationToken.of(session.getId(), session.getMaxIdleTime().getSeconds(),
                session.getLastAccessTime().getEpochSecond());
    }

}
