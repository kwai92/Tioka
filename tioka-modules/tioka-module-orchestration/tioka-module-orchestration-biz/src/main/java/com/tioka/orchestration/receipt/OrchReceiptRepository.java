package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 回执回写所需的 Run / Fact / WorkItem / Timer 访问（PK-13）。
 */
@Repository
public class OrchReceiptRepository {

    private final OrchRunMapper runs;
    private final OrchRuleVersionMapper versions;
    private final OrchFactMapper facts;
    private final OrchWorkItemMapper workItems;
    private final OrchTimerMapper timers;
    private final ObjectMapper mapper;

    public OrchReceiptRepository(OrchRunMapper runs,
                                 OrchRuleVersionMapper versions,
                                 OrchFactMapper facts,
                                 OrchWorkItemMapper workItems,
                                 OrchTimerMapper timers,
                                 ObjectMapper mapper) {
        this.runs = runs;
        this.versions = versions;
        this.facts = facts;
        this.workItems = workItems;
        this.timers = timers;
        this.mapper = mapper;
    }

    public Optional<OrchRunDO> findRun(String runId) {
        return Optional.ofNullable(runs.selectById(runId));
    }

    public Optional<OrchRuleVersionDO> findVersion(String versionId) {
        return Optional.ofNullable(versions.selectById(versionId));
    }

    public void updateRun(OrchRunDO run) {
        run.setUpdateAt(LocalDateTime.now());
        runs.updateById(run);
    }

    public void appendFact(String runId, String kind, Map<String, Object> detail) {
        int next = nextFactSeq(runId);
        OrchFactDO row = new OrchFactDO();
        row.setRunId(runId);
        row.setSeq(next);
        row.setKind(kind);
        row.setDetailJson(writeJson(detail == null ? Map.of() : detail));
        row.setAt(LocalDateTime.now());
        facts.insert(row);
    }

    public void clearActiveRun(String workItemId, String runId) {
        if (workItemId == null || workItemId.isBlank()) {
            return;
        }
        OrchWorkItemDO wi = workItems.selectById(workItemId);
        if (wi == null) {
            return;
        }
        if (runId != null && runId.equals(wi.getActiveRunId())) {
            wi.setActiveRunId(null);
            wi.setUpdateAt(LocalDateTime.now());
            workItems.updateById(wi);
        }
    }

    public void upsertLaterTimer(UpsertLaterCommand cmd) {
        OrchTimerDO existing = timers.selectOne(Wrappers.<OrchTimerDO>lambdaQuery()
                .eq(OrchTimerDO::getWorkItemId, cmd.workItemId())
                .eq(OrchTimerDO::getTimerKey, cmd.timerKey())
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            OrchTimerDO row = new OrchTimerDO();
            row.setId(Ids.newId("tmr"));
            row.setWorkspaceId(cmd.workspaceId());
            row.setKind("LATER");
            row.setWorkItemId(cmd.workItemId());
            row.setTimerKey(cmd.timerKey());
            row.setNextFireAt(cmd.nextFireAt());
            row.setMisfire("LATEST_ONLY");
            row.setTargetJson(writeJson(cmd.target()));
            row.setStatus("ACTIVE");
            row.setCreateAt(now);
            row.setUpdateAt(now);
            timers.insert(row);
            return;
        }
        existing.setNextFireAt(cmd.nextFireAt());
        existing.setTargetJson(writeJson(cmd.target()));
        existing.setStatus("ACTIVE");
        existing.setUpdateAt(now);
        timers.updateById(existing);
    }

    private int nextFactSeq(String runId) {
        List<OrchFactDO> list = facts.selectList(Wrappers.<OrchFactDO>lambdaQuery()
                .eq(OrchFactDO::getRunId, runId)
                .orderByDesc(OrchFactDO::getSeq)
                .last("LIMIT 1"));
        if (list.isEmpty() || list.get(0).getSeq() == null) {
            return 1;
        }
        return list.get(0).getSeq() + 1;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** later 定时 upsert 入参。 */
    public record UpsertLaterCommand(
            String workspaceId,
            String workItemId,
            String timerKey,
            LocalDateTime nextFireAt,
            Map<String, Object> target) {
    }
}
