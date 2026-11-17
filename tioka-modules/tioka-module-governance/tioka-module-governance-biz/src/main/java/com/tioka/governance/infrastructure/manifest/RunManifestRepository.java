package com.tioka.governance.infrastructure.manifest;

import com.tioka.governance.infrastructure.mapper.RunManifestMapper;
import com.tioka.governance.infrastructure.persistence.RunManifestDO;

import java.time.Instant;
import java.util.List;

/**
 * 运行清单读写（OBS-06 / ADR-075 §3）。
 * <p>由 {@code RunManifestAutoConfiguration} 装配（不经全局 ComponentScan）。
 * 只增不改；空间隔离在服务层按 {@code workspace_id} 显式校验（Mapper 旁路 ScopeLine）。
 */
public class RunManifestRepository {

    private final RunManifestMapper mapper;

    public RunManifestRepository(RunManifestMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(RunManifestDO row) {
        mapper.insert(row);
    }

    public RunManifestDO findById(String id) {
        return mapper.findById(id);
    }

    public RunManifestDO findLatest(String runKind, String runId) {
        return mapper.findLatest(runKind, runId);
    }

    public List<RunManifestDO> listByRun(String runKind, String runId) {
        return mapper.listByRun(runKind, runId);
    }

    /** 同类运行（runKind + agent 版本）近期清单，供差异对比；{@code limit} 上限 200。 */
    public List<RunManifestDO> listRecentSameKind(String workspaceId, String runKind,
                                                 String agentVersionId, int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return mapper.listRecentSameKind(workspaceId, runKind, agentVersionId, capped);
    }

    /** OBS-08 时间线重建：按落库时间区间取清单（{@code null} 表示不限）。 */
    public List<RunManifestDO> listByRange(Instant from, Instant to) {
        return mapper.listByRange(from, to);
    }

    /**
     * OBS-12 证据包：按请求 / 主体取关联清单，按落库时间升序；{@code limit} 上限 500。
     * <p>两个关联键都为空时直接返回空表，避免退化为全表扫描。
     */
    public List<RunManifestDO> listByScope(String workspaceId, String requestId,
                                           String subject, int limit) {
        boolean hasRequest = requestId != null && !requestId.isBlank();
        boolean hasSubject = subject != null && !subject.isBlank();
        if (!hasRequest && !hasSubject) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, 500));
        return mapper.listByScope(workspaceId, requestId, subject, capped);
    }
}
