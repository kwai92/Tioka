package com.tioka.orchestration.timer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import com.tioka.orchestration.receipt.OrchTimerDO;
import com.tioka.orchestration.receipt.OrchTimerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * orch_timer 到期派发与 CRON 续算（ORC-06）。
 */
@Service
public class OrchTimerService {

    private static final Logger log = LoggerFactory.getLogger(OrchTimerService.class);
    private static final int CLAIM_LIMIT = 100;
    private static final int ALL_MISFIRE_CAP = 100;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final OrchTimerMapper timers;
    private final OrchApi orchApi;
    private final OrchEngineRepository engine;
    private final ObjectMapper mapper;

    public OrchTimerService(OrchTimerMapper timers,
                            OrchApi orchApi,
                            OrchEngineRepository engine,
                            ObjectMapper mapper) {
        this.timers = timers;
        this.orchApi = orchApi;
        this.engine = engine;
        this.mapper = mapper;
    }

    /** 领取并触发到期定时器；返回触发条数。 */
    @Transactional
    public int pollOnce() {
        LocalDateTime now = LocalDateTime.now();
        List<OrchTimerDO> due = timers.claimDue(now, CLAIM_LIMIT);
        int fired = 0;
        for (OrchTimerDO row : due) {
            try {
                fireOne(row, now);
                fired++;
            } catch (Exception e) {
                log.warn("orch timer fire failed id={}: {}", row.getId(), e.toString());
                throw e;
            }
        }
        return fired;
    }

    private void fireOne(OrchTimerDO row, LocalDateTime now) {
        if ("CRON".equals(row.getKind()) && "SKIP".equals(row.getMisfire())) {
            advanceCron(row, now, false);
            return;
        }
        if ("CRON".equals(row.getKind()) && "ALL".equals(row.getMisfire())) {
            fireCronAll(row, now);
            return;
        }
        // LATER 或 CRON LATEST_ONLY：触发一次
        ingest(row, row.getNextFireAt());
        if ("LATER".equals(row.getKind())) {
            row.setStatus("DONE");
            row.setUpdateAt(LocalDateTime.now());
            timers.updateById(row);
            return;
        }
        advanceCron(row, now, true);
    }

    private void fireCronAll(OrchTimerDO row, LocalDateTime now) {
        CronExpression expr = parseCron(row);
        ZoneId zone = zoneOf(row);
        ZonedDateTime cursor = row.getNextFireAt().atZone(zone);
        int n = 0;
        while (!cursor.toLocalDateTime().isAfter(now) && n < ALL_MISFIRE_CAP) {
            ingest(row, cursor.toLocalDateTime());
            ZonedDateTime next = expr.next(cursor);
            if (next == null) {
                row.setStatus("DONE");
                row.setUpdateAt(LocalDateTime.now());
                timers.updateById(row);
                return;
            }
            cursor = next;
            n++;
        }
        row.setNextFireAt(cursor.toLocalDateTime());
        row.setUpdateAt(LocalDateTime.now());
        timers.updateById(row);
    }

    private void advanceCron(OrchTimerDO row, LocalDateTime now, boolean alreadyFiredLatest) {
        CronExpression expr = parseCron(row);
        ZoneId zone = zoneOf(row);
        ZonedDateTime from = alreadyFiredLatest
                ? now.atZone(zone)
                : row.getNextFireAt().atZone(zone);
        ZonedDateTime next = expr.next(from);
        if (next == null) {
            row.setStatus("DONE");
        } else {
            row.setNextFireAt(next.toLocalDateTime());
        }
        row.setUpdateAt(LocalDateTime.now());
        timers.updateById(row);
    }

    private void ingest(OrchTimerDO row, LocalDateTime fireAt) {
        Map<String, Object> target = readTarget(row.getTargetJson());
        String type = str(target.get("eventType"));
        if (type == null || type.isBlank()) {
            type = "orch.later";
        }
        String subjectKey = str(target.get("subjectKey"));
        if (subjectKey == null || subjectKey.isBlank()) {
            OrchWorkItemDO wi = row.getWorkItemId() == null ? null : engine.findWorkItem(row.getWorkItemId());
            subjectKey = wi == null || wi.getSubjectKey() == null ? row.getId() : wi.getSubjectKey();
        }
        Map<String, String> attrs = stringMap(target.get("attrs"));
        attrs = new LinkedHashMap<>(attrs);
        attrs.putIfAbsent("timerId", row.getId());
        attrs.putIfAbsent("timerKey", row.getTimerKey() == null ? "" : row.getTimerKey());
        Map<String, Object> body = new LinkedHashMap<>();
        if (target.get("input") != null) {
            body.put("input", target.get("input"));
        }
        if (target.get("runId") != null) {
            body.put("runId", target.get("runId"));
        }
        body.putAll(mapOf(target.get("body")));
        String idem = row.getId() + ":" + fireAt;
        orchApi.ingest(new OrchApi.EventEnvelope(
                "timer", type, idem, subjectKey, attrs, body, 0));
    }

    public OrchTimerDO createLater(String workspaceId, String workItemId, String timerKey,
                                   LocalDateTime nextFireAt, Map<String, Object> target) {
        OrchTimerDO existing = timers.selectOne(Wrappers.<OrchTimerDO>lambdaQuery()
                .eq(OrchTimerDO::getWorkItemId, workItemId)
                .eq(OrchTimerDO::getTimerKey, timerKey)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            OrchTimerDO row = new OrchTimerDO();
            row.setId(Ids.newId("tmr"));
            row.setWorkspaceId(workspaceId);
            row.setKind("LATER");
            row.setWorkItemId(workItemId);
            row.setTimerKey(timerKey);
            row.setNextFireAt(nextFireAt);
            row.setMisfire("LATEST_ONLY");
            row.setTargetJson(writeJson(target == null ? Map.of() : target));
            row.setStatus("ACTIVE");
            row.setCreateAt(now);
            row.setUpdateAt(now);
            timers.insert(row);
            return row;
        }
        existing.setNextFireAt(nextFireAt);
        existing.setTargetJson(writeJson(target == null ? Map.of() : target));
        existing.setStatus("ACTIVE");
        existing.setUpdateAt(now);
        timers.updateById(existing);
        return existing;
    }

    public OrchTimerDO createCron(String workspaceId, String ruleId, String cron, String tz,
                                  String misfire, Map<String, Object> target) {
        CronExpression expr = CronExpression.parse(cron);
        ZoneId zone = ZoneId.of(tz == null || tz.isBlank() ? "UTC" : tz);
        ZonedDateTime next = expr.next(ZonedDateTime.now(zone));
        if (next == null) {
            throw new IllegalArgumentException("cron 无下一触发点: " + cron);
        }
        OrchTimerDO row = new OrchTimerDO();
        row.setId(Ids.newId("tmr"));
        row.setWorkspaceId(workspaceId);
        row.setKind("CRON");
        row.setRuleId(ruleId);
        row.setTimerKey("cron:" + ruleId);
        row.setCron(cron);
        row.setTz(zone.getId());
        row.setNextFireAt(next.toLocalDateTime());
        row.setMisfire(misfire == null || misfire.isBlank() ? "LATEST_ONLY" : misfire);
        row.setTargetJson(writeJson(target == null ? Map.of() : target));
        row.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        row.setCreateAt(now);
        row.setUpdateAt(now);
        timers.insert(row);
        return row;
    }

    public List<OrchTimerDO> list(String workspaceId, String status) {
        return timers.selectList(Wrappers.<OrchTimerDO>lambdaQuery()
                .eq(workspaceId != null && !workspaceId.isBlank(), OrchTimerDO::getWorkspaceId, workspaceId)
                .eq(status != null && !status.isBlank(), OrchTimerDO::getStatus, status)
                .orderByAsc(OrchTimerDO::getNextFireAt)
                .last("LIMIT 200"));
    }

    public void cancel(String id) {
        OrchTimerDO row = timers.selectById(id);
        if (row == null) {
            return;
        }
        row.setStatus("CANCELLED");
        row.setUpdateAt(LocalDateTime.now());
        timers.updateById(row);
    }

    public OrchTimerDO get(String id) {
        return timers.selectById(id);
    }

    private CronExpression parseCron(OrchTimerDO row) {
        if (row.getCron() == null || row.getCron().isBlank()) {
            throw new IllegalStateException("CRON timer 缺少 cron 表达式: " + row.getId());
        }
        return CronExpression.parse(row.getCron());
    }

    private ZoneId zoneOf(OrchTimerDO row) {
        String tz = row.getTz();
        return ZoneId.of(tz == null || tz.isBlank() ? "UTC" : tz);
    }

    private Map<String, Object> readTarget(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (k != null) {
                    out.put(String.valueOf(k), v);
                }
            });
            return out;
        }
        return Map.of();
    }
}
