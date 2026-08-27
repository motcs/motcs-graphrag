package com.motcs.commons.annotation;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.log4j.Log4j2;

import java.io.Serializable;
import java.util.List;

/**
 * @author <a href="https://github.com/motcs">motcs</a>
 * @since 2026-08-25 星期二
 */

@Log4j2
@Data
@EqualsAndHashCode(callSuper = true)
public class RestServerException extends RuntimeException implements Serializable {

    protected Object msg;

    protected int code;

    public RestServerException(String message) {
        super(message);
    }

    public RestServerException(String message, Throwable cause) {
        super(message, cause);
    }

    public RestServerException(int code, String message, Object errors) {
        super(message);
        this.code = code;
    }

    public static RestServerException withMsg(int code, String message, Object errors) {
        return new RestServerException(code, message, errors);
    }

    public static RestServerException withMsg(String message, Object errors) {
        return RestServerException.withMsg(1500, message, errors);
    }

    public static RestServerException withMsg(String msg) {
        return RestServerException.withMsg(1500, msg, "服务自定义错误，具体错误信息，请查看msg!");
    }

    public static RestServerException withMsg(int code, String message) {
        return RestServerException.withMsg(code, message, List.of());
    }

}
