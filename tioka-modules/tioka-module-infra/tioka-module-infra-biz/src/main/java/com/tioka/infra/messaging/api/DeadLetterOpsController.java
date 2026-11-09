package com.tioka.infra.messaging.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.infra.messaging.DeadLetterOpsService;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import com.tioka.system.api.audit.Audited;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ORC-07 死信运维出口（FE-17 死信列表、重放、丢弃）。
 *
 * <p>权限 {@code orch:operate}（M2-01 ORC-07 管理端表）；列表 payload 只返回前 2000 字。
 * 丢弃为破坏性操作：审计动作（deadletter.discard / deadletter.replay）待 actions.yaml
 * 统一登记后补挂 @Audited（M2-B6b 批次文档已登记，ADR-072 先登记再使用）。
 */
@RestController
@RequestMapping("/api/ops/dead-letters")
@RequirePermission(Permissions.ORCH_OPERATE)
public class DeadLetterOpsController {

    /** 列表 payload 预览长度（M2-01：「payload 只返回前 2000 字」）。 */
    static final int PAYLOAD_PREVIEW = 2000;

    private final DeadLetterOpsService ops;
    private final PrincipalHolder principals;

    public DeadLetterOpsController(DeadLetterOpsService ops, PrincipalHolder principals) {
        this.ops = ops;
        this.principals = principals;
    }

    public record DeadLetterView(
            String id, String consumer, String topic, String tag, String eventId,
            String error, Integer attempts, String status,
            Instant createAt, String handledBy, Instant handledAt, String payload) {}

    @GetMapping
    public ApiResponse<DeadLetterOpsService.Page<DeadLetterView>> list(
            @RequestParam(required = false) String consumer,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        DeadLetterOpsService.Page<SysDeadLetterDO> result = ops.list(consumer, status, page, size);
        List<DeadLetterView> views = new ArrayList<>();
        for (SysDeadLetterDO row : result.items()) {
            views.add(toView(row));
        }
        return ApiResponse.ok(new DeadLetterOpsService.Page<>(
                views, result.total(), result.page(), result.size()));
    }

    @PostMapping("/{id}/replay")
    @Audited(action = "deadletter.replay", resourceType = "sys_dead_letter", resourceIdArg = "#id")
    public ApiResponse<DeadLetterView> replay(@PathVariable String id) {
        return ApiResponse.ok(toView(ops.replay(id, handledBy())));
    }

    @PostMapping("/{id}/discard")
    @Audited(action = "deadletter.discard", resourceType = "sys_dead_letter", resourceIdArg = "#id")
    public ApiResponse<DeadLetterView> discard(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reason");
        return ApiResponse.ok(toView(ops.discard(id, handledBy(), reason)));
    }

    private String handledBy() {
        Principal current = principals.current();
        return current == null ? null : current.userId();
    }

    private static DeadLetterView toView(SysDeadLetterDO row) {
        String payload = row.getPayloadJson();
        if (payload != null && payload.length() > PAYLOAD_PREVIEW) {
            payload = payload.substring(0, PAYLOAD_PREVIEW);
        }
        return new DeadLetterView(row.getId(), row.getConsumer(), row.getTopic(), row.getTag(),
                row.getEventId(), row.getError(), row.getAttempts(), row.getStatus(),
                row.getCreateAt(), row.getHandledBy(), row.getHandledAt(), payload);
    }
}
