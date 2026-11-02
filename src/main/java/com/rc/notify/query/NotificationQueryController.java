package com.rc.notify.query;

import com.rc.notify.config.AuthProperties;
import com.rc.notify.ingest.ApiKeyAuthFilter;
import com.rc.notify.query.dto.NotificationDetailResponse;
import com.rc.notify.query.dto.NotificationPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询接口（T3.1）：单号详情 + 按状态拉列表。
 *
 * <p>与 {@code IngestController} 共用前缀 {@code /v1/notifications}，靠 HTTP 方法区分——
 * 资源路径不该因为"操作不同"而分叉（`/getNotification` 那种风格）。
 *
 * <p>caller 一律从 {@link ApiKeyAuthFilter} 注入的凭证派生，<b>不接受任何请求参数指定 caller</b>：
 * 一旦允许 `?caller=xxx`，越权就只差一个字符串。
 */
@RestController
@RequestMapping("/v1/notifications")
public class NotificationQueryController {

    private final NotificationQueryService service;

    public NotificationQueryController(NotificationQueryService service) {
        this.service = service;
    }

    /**
     * 查单条：状态轨迹 + 每轮 attempt 明细。
     *
     * <p>{@code id} 用 {@code long} 接：`/abc` 会在这里触发类型转换失败，由
     * {@code ApiExceptionHandler} 转成 400 而不是 500（见该类的注释）。
     */
    @GetMapping("/{id}")
    public NotificationDetailResponse detail(
            @PathVariable long id,
            @RequestAttribute(ApiKeyAuthFilter.ATTR_CALLER) AuthProperties.Client caller) {
        return service.detail(id, caller.callerId());
    }

    /**
     * 列表：默认按单号倒序、键集分页。
     *
     * @param status 状态过滤（大小写不敏感）；缺省不过滤
     * @param cursor 上一页的 {@code next_cursor}；缺省为第一页
     * @param limit  页大小，1..100，缺省 20
     */
    @GetMapping
    public NotificationPage list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestAttribute(ApiKeyAuthFilter.ATTR_CALLER) AuthProperties.Client caller) {
        return service.list(caller.callerId(), status, cursor, limit);
    }
}
