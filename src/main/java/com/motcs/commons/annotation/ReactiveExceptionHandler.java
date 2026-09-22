package com.motcs.commons.annotation;

import io.r2dbc.spi.R2dbcException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * @author billb
 */
@Log4j2
@ControllerAdvice
public class ReactiveExceptionHandler {

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleBindException(ServerWebExchange exchange, ServerWebInputException ex) {
        List<String> errors = new ArrayList<>();
        errors.add(ex.getLocalizedMessage());
        if (ex instanceof WebExchangeBindException bindException) {
            errors = bindException.getBindingResult().getAllErrors().parallelStream()
                    .map(DefaultMessageSourceResolvable::getDefaultMessage).collect(Collectors.toList());
        } else {
            errors.add(Optional.ofNullable(ex.getCause()).map(Throwable::getMessage).orElse(ex.getMessage()));
            errors.add(ex.getReason());
        }
        log.error("ServerWebInputException 请求参数验证失败!{}", errors, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(exchange.getRequest().getId(), exchange.getRequest().getPath().value(),
                        4071, "请求参数验证失败!" + errors.getFirst(), null));
    }

    @ExceptionHandler({DataAccessException.class, R2dbcException.class})
    public ResponseEntity<ErrorResponse> handleLockingFailureException(ServerWebExchange exchange, RuntimeException ex) {
        List<String> errors = new ArrayList<>();
        ErrorResponse errorResponse;
        if (ex instanceof R2dbcException r2dbcException) {
            errors.add(r2dbcException.getLocalizedMessage());
            errors.add(r2dbcException.getSql());
            errors.add(r2dbcException.getSqlState());
            log.debug("R2dbcException 数据库操作SQL:[{}]", r2dbcException.getSql());
            errorResponse = ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(),
                    5071, "R2dbcException 数据库操作失败!", null);
        } else if (ex instanceof BadSqlGrammarException grammarException) {
            errors.add(grammarException.getCause().getMessage());
            errors.add(grammarException.getSql());
            log.debug("GrammarException 数据库操作SQL:[{}]", grammarException.getSql());
            errorResponse = ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(),
                    5071, "GrammarException，数据库操作失败!", null);
        } else {
            errors.add(ex.getLocalizedMessage());
            errorResponse = ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(),
                    5071, ex.getCause().getMessage(), "参数异常，请不要随意修改参数格式!");
        }
        log.error("DataAccessException 数据库操作失败!:{}", errors, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(errorResponse);
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ErrorResponse> handleClientException(ServerWebExchange exchange, WebClientException ex) {
        log.error("WebClientException 内部服务访问错误!{}", List.of(ex.getMessage(), ex.getLocalizedMessage()), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(), 1504,
                        "%s 内部服务访问错误!".formatted(ex.getLocalizedMessage()), null));
    }

    @ExceptionHandler(RestServerException.class)
    public ResponseEntity<ErrorResponse> handleRestServerException(ServerWebExchange exchange, RestServerException ex) {
        log.error("RestServerException 服务器自定义错误!{}", ex.getMessage(), ex);
        if (StringUtils.hasLength(ex.getMessage()) && ex.getMessage().startsWith("LoadBalancer does not contain an instance for the service")) {
            String substring = ex.getMessage().substring("LoadBalancer does not contain an instance for the service".length()).trim();
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(), 5000,
                            substring + " 服务未启动，请稍后重试！", null));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(), ex.getCode(),
                        ex.getMessage(), "服务出现错误！"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknownException(ServerWebExchange exchange, Exception ex) {
        log.error("Exception 服务器未知错误!", ex);
        if (ex instanceof ConstraintViolationException exception) {
            List<String> stringList = exception.getConstraintViolations()
                    .stream().map(ConstraintViolation::getMessageTemplate).toList();
            log.error("服务器出现ConstraintViolationException 错误：{}", stringList);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(),
                            5000, exception.getMessage(), "服务器出现ConstraintViolationException 错误!"));
        } else if (ex instanceof BadCredentialsException be) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(),
                            5000, be.getMessage(), "认证出现了异常！"));
        }
        log.error("服务器出现未知错误：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(exchange.getLogPrefix(), exchange.getRequest().getPath().value(),
                        5000, ex.getMessage(), "服务器出现未知错误!"));
    }

}