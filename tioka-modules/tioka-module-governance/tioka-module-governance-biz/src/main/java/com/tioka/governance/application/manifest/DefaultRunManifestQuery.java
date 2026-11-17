package com.tioka.governance.application.manifest;

import com.tioka.governance.api.manifest.RunManifestQuery;
import com.tioka.governance.api.manifest.RunManifestView;
import com.tioka.governance.infrastructure.manifest.RunManifestRepository;
import com.tioka.governance.infrastructure.persistence.RunManifestDO;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 运行清单查询（OBS-06 / ADR-075 §3）：时间线展开与同类运行差异对比。
 */
public class DefaultRunManifestQuery implements RunManifestQuery {

    private final RunManifestRepository repository;

    public DefaultRunManifestQuery(RunManifestRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RunManifestView> listByRun(String runKind, String runId) {
        return repository.listByRun(runKind, runId).stream().map(DefaultRunManifestQuery::toView).toList();
    }

    @Override
    public List<RunManifestView> listRecentSameKind(String workspaceId, String runKind,
                                                    String agentVersionId, int limit) {
        return repository.listRecentSameKind(workspaceId, runKind, agentVersionId, limit)
                .stream().map(DefaultRunManifestQuery::toView).toList();
    }

    @Override
    public List<RunManifestView> listByScope(String workspaceId, String requestId,
                                             String subject, int limit) {
        return repository.listByScope(workspaceId, requestId, subject, limit)
                .stream().map(DefaultRunManifestQuery::toView).toList();
    }

    static RunManifestView toView(RunManifestDO row) {
        return new RunManifestView(
                row.getId(),
                row.getRunKind(),
                row.getRunId(),
                row.getSeq() == null ? 0 : row.getSeq(),
                row.getRequestId(),
                row.getWorkspaceId(),
                row.getSubject(),
                row.getTraceId(),
                row.getAgentVersionId(),
                row.getSpecHash(),
                row.getGrantId(),
                row.getManifestJson(),
                row.getContentHash(),
                row.getCreateAt() == null ? null : row.getCreateAt().toInstant(ZoneOffset.UTC));
    }
}
