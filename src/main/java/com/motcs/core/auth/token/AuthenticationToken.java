package com.motcs.core.auth.token;

import java.io.Serializable;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-09-07 星期一
 */
public record AuthenticationToken(String token, Long expires, Long lastAccessTime) implements Serializable {

    public static AuthenticationToken of(String token, Long expires, Long lastAccessTime) {
        return new AuthenticationToken(token, expires, lastAccessTime);
    }

}
