package com.rc.notify.api;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常：携带 HTTP 状态与稳定错误码，由 {@link ApiExceptionHandler} 统一转成错误体。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message);
    }

    public static ApiException duplicateIdempotencyKey() {
        return new ApiException(HttpStatus.CONFLICT, "DUPLICATE_IDEMPOTENCY_KEY",
                "同 caller 同 idempotency_key 的通知已存在（T1.3 将改为返回 202 + 原单号）");
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
