package com.tioka.system.application.obs;

import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.system.infrastructure.persistence.AuthUserDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code auth_user} → 时间线 {@code CONFIG} 来源事件（OBS-12 / ADR-075 §4 用户主体时间线）。
 *
 * <p>逐用户投影（一个账号一条）：主体固定为 {@code user:<userId>}，标题区分「用户 / 服务账号」
 * 并标注停用态，使管理员可从用户主体时间线查到账号的建立与变更
 * （ADR-075 §4：用户主体时间线供管理员调查用，查看侧需 {@code audit:read:all}）。
 *
 * <p>空间取账号的 {@code default_workspace_id}；数据来源为账号表本身（{@code auth_user} 无
 * {@code workspace_id} 列，属 ScopeLine 旁路表）。投影按 {@code sourceEventId} 幂等。
 */
public final class UserTimelineProjection {

    /** {@code timeline_entry.source_type}；重建时按此值清除区间内条目。 */
    public static final String SOURCE_TYPE = "auth_user";

    /** 主体类型：用户（ADR-075 §4）。 */
    public static final String SUBJECT_TYPE = "user";

    public static TimelineSourceEvent toSourceEvent(AuthUserDO row) {
        if (row == null || row.getId() == null) {
            return null;
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("category", "CONFIG");
        attrs.put("title", title(row));
        attrs.put("sourceType", SOURCE_TYPE);
        attrs.put("sourceId", String.valueOf(row.getId()));
        attrs.put("outcome", "OK");
        return new TimelineSourceEvent(
                SOURCE_TYPE + ":" + row.getId(),
                GovernanceMqTopics.CONFIG_RECORDED,
                null,
                null,
                SUBJECT_TYPE,
                String.valueOf(row.getId()),
                row.getDefaultWorkspaceId(),
                occurredAt(row),
                attrs);
    }

    private static String title(AuthUserDO row) {
        StringBuilder sb = new StringBuilder(isService(row) ? "服务账号：" : "用户：");
        String name = row.getDisplayName();
        if (name == null || name.isBlank()) {
            name = row.getUsername();
        }
        sb.append(name == null || name.isBlank() ? row.getId() : name);
        if (Boolean.FALSE.equals(row.getEnabled())) {
            sb.append("（已停用）");
        }
        return sb.toString();
    }

    private static boolean isService(AuthUserDO row) {
        return "SERVICE".equalsIgnoreCase(row.getType());
    }

    private static Instant occurredAt(AuthUserDO row) {
        LocalDateTime at = row.getUpdateAt() == null ? row.getCreateAt() : row.getUpdateAt();
        return at == null ? Instant.now() : at.toInstant(ZoneOffset.UTC);
    }

    private UserTimelineProjection() {
    }
}
