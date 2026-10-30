package com.rc.notify.ingest;

import com.rc.notify.config.AuthProperties;
import com.rc.notify.ingest.dto.SubmitNotificationRequest;
import com.rc.notify.ingest.dto.SubmitNotificationResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接入接口（T1.1）：只做参数接收与状态码，不含业务判断。
 *
 * <p>返回 202 而非 200：语义是"已接收，处理未完成"——落库成功代表可靠性义务开始，
 * 但投递由 T1.2 异步完成（T1.1 文档 §2.1）。
 */
@RestController
@RequestMapping("/v1/notifications")
public class IngestController {

    private final NotificationService service;

    public IngestController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SubmitNotificationResponse> submit(
            @Valid @RequestBody SubmitNotificationRequest request,
            @RequestAttribute(ApiKeyAuthFilter.ATTR_CALLER) AuthProperties.Client caller) {
        SubmitNotificationResponse response = service.submit(request, caller);
        return ResponseEntity.accepted().body(response);
    }
}
