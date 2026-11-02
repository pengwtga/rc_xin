package com.rc.notify.replay;

import com.rc.notify.config.AuthProperties;
import com.rc.notify.ingest.ApiKeyAuthFilter;
import com.rc.notify.replay.dto.ReplayRequest;
import com.rc.notify.replay.dto.ReplayResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工重放（T3.2）：`POST /v1/notifications/{id}/replay`。
 *
 * <p>挂在既有资源 `/v1/notifications/{id}` 下面而不是 `/v1/replays`：重放是"对这条通知
 * 做的一个动作"，REST 语义上属于子资源；路径也直接回答了"重放的是哪条"。
 *
 * <p>返回 200 而不是 202：状态迁移在调用返回前已经提交（同步小事务，无队列），
 * 202 的"已受理未完成"语义用不上。
 */
@RestController
public class ReplayController {

    private final ReplayService service;

    public ReplayController(ReplayService service) {
        this.service = service;
    }

    @PostMapping("/v1/notifications/{id}/replay")
    public ReplayResponse replay(
            @PathVariable long id,
            @Valid @RequestBody ReplayRequest request,
            @RequestAttribute(ApiKeyAuthFilter.ATTR_CALLER) AuthProperties.Client caller) {
        return service.replay(id, caller, request);
    }
}
