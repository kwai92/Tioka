package com.tioka.system.application.obs;

import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.api.timeline.TimelineSourceEvent;
import com.tioka.system.infrastructure.AuthUserRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code auth_user} 的时间线重建来源（OBS-12 / ADR-075 §4 用户主体时间线）：
 * 读回区间内的账号，按 {@link UserTimelineProjection} 重新投影 {@code CONFIG} 条目。
 *
 * <p>由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集；
 * system 的自动配置为无条件组件扫描，故 {@code @Component} 即可。经 {@link AuthUserRepository}
 * 读库（application 不直持 Mapper）。
 */
@Component
public class UserTimelineRebuildSource implements TimelineRebuildSource {

    private final AuthUserRepository users;

    public UserTimelineRebuildSource(AuthUserRepository users) {
        this.users = users;
    }

    @Override
    public String sourceType() {
        return UserTimelineProjection.SOURCE_TYPE;
    }

    @Override
    public List<TimelineSourceEvent> eventsInRange(Instant from, Instant to) {
        List<TimelineSourceEvent> out = new ArrayList<>();
        for (var row : users.listByRange(from, to)) {
            TimelineSourceEvent event = UserTimelineProjection.toSourceEvent(row);
            if (event != null) {
                out.add(event);
            }
        }
        return out;
    }
}
