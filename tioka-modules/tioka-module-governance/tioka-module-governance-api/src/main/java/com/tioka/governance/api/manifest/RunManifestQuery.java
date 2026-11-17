package com.tioka.governance.api.manifest;

import java.util.List;

/**
 * 运行清单查询端口（OBS-06 / ADR-075 §3）。
 * <p>调用方负责空间隔离：仅传入当前主体可见的 {@code workspaceId}。
 */
public interface RunManifestQuery {

    /** 某次运行的全部清单，按 {@code seq} 升序。 */
    List<RunManifestView> listByRun(String runKind, String runId);

    /**
     * 同类运行（{@code runKind} + Agent 版本）的近期清单，按时间倒序，供差异对比。
     *
     * @param limit 1〜200，超出按上限裁剪
     */
    List<RunManifestView> listRecentSameKind(String workspaceId, String runKind,
                                             String agentVersionId, int limit);

    /**
     * 证据包（OBS-12 / ADR-075 §5）：按请求或主体取关联的运行清单，按落库时间升序。
     *
     * <p>{@code requestId} 与 {@code subject} 至少给一个（都为空返回空表，避免全表扫描）；
     * {@code workspaceId} 为 null 表示不限空间（仅平台级调用方）。
     *
     * @param limit 1〜500，超出按上限裁剪
     */
    List<RunManifestView> listByScope(String workspaceId, String requestId, String subject, int limit);
}
