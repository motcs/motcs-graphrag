package com.motcs.commons.annotation;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 该类为错误响应实体，实现 Serializable 接口
 * ErrorResponse entity class that implements the Serializable interface
 *
 * @author <a href="https://github.com/vnobo">Alex bob</a>
 */
public record ErrorResponse(String requestId, String path, Integer code, String message,
                            Object errors, LocalDateTime time) implements Serializable {
    public static ErrorResponse of(String requestId, String path, Integer code, String message, Object errors) {
        return new ErrorResponse(requestId, path, code, message, errors, LocalDateTime.now());
    }
}