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

    /**
     * 资源不存在（T3.1）。
     *
     * <p>越权查询与真的不存在<b>用同一个响应</b>——若"别人的单"返回 403、"不存在的单"返回 404，
     * 就等于提供了一个单号存在性探测接口。内部系统的单号虽然不可枚举，但也没必要开这个口子。
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /**
     * 参数格式非法（T3.1）：路径变量或查询参数<b>解析不出类型</b>（`/abc`、`cursor=xyz`）。
     *
     * <p>与 422 的分界线沿用本系统既有口径：400 是"请求本身无法处理"（类型层面就失败），
     * 422 是"结构可解析但内容不合法"（`status=FOO`、`limit=0`）。
     */
    public static ApiException invalidParameter(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
