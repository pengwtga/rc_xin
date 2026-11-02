package com.rc.notify.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 统一异常映射（T1.1）。
 *
 * <p>关键点：{@code @Valid} 失败在 Spring 默认返回 400，本系统按设计文档 §5 统一为 422 ——
 * 400 表示"请求本身无法处理"，422 表示"结构可解析但内容不合法"，后者对调用方更有指导意义。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ApiError(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("VALIDATION_FAILED", detail.isBlank() ? "参数校验失败" : detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("VALIDATION_FAILED", "请求体不是合法 JSON"));
    }

    /**
     * 类型转换失败（T3.1 补的坑）。
     *
     * <p>{@code GET /v1/notifications/abc} 的 `{id}` 绑不进 `long`，Spring 抛
     * {@link MethodArgumentTypeMismatchException}。它默认落到下面的 {@code Exception} 兜底里，
     * 于是调用方看到的是 **500 服务内部错误** —— 一个纯客户端错误被打成服务端故障，
     * 既误导排查（告警会响），也让"参数写错"和"我们的 bug"混在一起。
     *
     * <p>这里回显参数名与出错的值（截断到 64 字符）：参数问题要让人一眼看到自己传了什么，
     * 但不把可能长达数 KB 的原始值原样吐回去。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String raw = String.valueOf(e.getValue());
        if (raw.length() > 64) {
            raw = raw.substring(0, 64) + "…";
        }
        return ResponseEntity.badRequest().body(new ApiError("INVALID_PARAMETER",
                "参数 %s 格式非法: %s（期望 %s）".formatted(
                        e.getName(), raw, e.getRequiredType() == null ? "?" : e.getRequiredType().getSimpleName())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleOther(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("INTERNAL", "服务内部错误"));
    }
}
