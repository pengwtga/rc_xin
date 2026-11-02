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
    /**
     * 建议的重试间隔（秒），仅 503 背压使用；为 null 时不发 {@code Retry-After}。
     *
     * <p>放在异常上而不是让 Handler 猜：这个值来自 {@code notify.backpressure.retry-after-seconds}，
     * 只有抛出方（{@link com.rc.notify.ingest.BackpressureGuard}）知道该让调用方等多久。
     */
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
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

    /**
     * 当前状态不允许该操作（T3.2）。
     *
     * <p>只用于"资源存在、也属于你，但状态不对"：对 PENDING/SUCCEEDED 单重放。
     * 与 404 严格区分——404 是"不是你的或不存在"，409 是"是你的，但它现在不在 DEAD"，
     * 调用方（运维脚本）需要靠这个码判断"该刷新列表再选一条"，而不是"别碰别人的单"。
     */
    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * 队列不健康，暂时无法受理（T3.3，设计文档 §4.8）。
     *
     * <p>与 429 的分界线：429 是"你发太多了"（按调用方的公平性，T3.5），
     * 503 是"系统整体接不动了"（容量问题）。前者换个调用方就好了，后者谁来都一样。
     *
     * <p>必须带 {@code Retry-After}：不告诉调用方等多久，他只能立刻重试——
     * 而立刻重试正是把背压变成雪崩的方式。
     */
    public static ApiException overloaded(String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "QUEUE_OVERLOADED", message, retryAfterSeconds);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
