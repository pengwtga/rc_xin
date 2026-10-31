package com.rc.notify.api;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常：携带 HTTP 状态与稳定错误码，由 {@link ApiExceptionHandler} 统一转成错误体。
 */
public class ApiException extends RuntimeException {

    /** 响应状态码，决定 HTTP 状态行（422/401/404...）。 */
    private final HttpStatus status;
    /** 稳定错误码，写入错误体供调用方程序判定（不随文案变化）。 */
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException validation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
