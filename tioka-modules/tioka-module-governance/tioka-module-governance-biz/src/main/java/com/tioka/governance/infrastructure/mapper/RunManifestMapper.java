package com.tioka.governance.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.governance.infrastructure.persistence.RunManifestDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 运行清单（OBS-06 / ADR-075 §3）：只增不改。
 * <p>写入发生在 Turn / Run 开始路径，可能无用户 Principal（如 Webhook 触发的 Flow Run），
 * 故旁路 ScopeLine，空间隔离在仓储 / 服务层按 {@code workspace_id} 显式校验。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface RunManifestMapper {

    int insert(RunManifestDO row);

    RunManifestDO findById(@Param("id") String id);

    /** 同一运行最近一条清单（用于「续跑有变化才追加」判定）。 */
    RunManifestDO findLatest(@Param("runKind") String runKind, @Param("runId") String runId);

    /** 同一运行的全部清单，按 seq 升序。 */
    List<RunManifestDO> listByRun(@Param("runKind") String runKind, @Param("runId") String runId);

    /** 同类运行（runKind + agent 版本）的近期清单，供差异对比。 */
    List<RunManifestDO> listRecentSameKind(@Param("workspaceId") String workspaceId,
                                          @Param("runKind") String runKind,
                                          @Param("agentVersionId") String agentVersionId,
                                          @Param("limit") int limit);

    /** OBS-08 时间线重建：按落库时间区间取清单（{@code from} / {@code to} 为 null 表示不限）。 */
    List<RunManifestDO> listByRange(@Param("from") Instant from, @Param("to") Instant to);

    /** OBS-12 证据包：按请求 / 主体取关联清单；调用方保证两键至少一个非空。 */
    List<RunManifestDO> listByScope(@Param("workspaceId") String workspaceId,
                                    @Param("requestId") String requestId,
                                    @Param("subject") String subject,
                                    @Param("limit") int limit);
}
