package com.tioka.governance.infrastructure.config;

import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.mapper.CfgDraftMapper;
import com.tioka.governance.infrastructure.mapper.CfgProjectionPointerMapper;
import com.tioka.governance.infrastructure.mapper.CfgVersionMapper;
import com.tioka.governance.infrastructure.persistence.CfgDraftDO;
import com.tioka.governance.infrastructure.persistence.CfgVersionDO;

import java.util.Optional;

/**
 * cfg_* 仓储门面（infrastructure；application 不直接碰 Mapper）。
 */
public class CfgStore {

    private final CfgDraftMapper drafts;
    private final CfgVersionMapper versions;
    private final CfgChangeMapper changes;
    private final CfgProjectionPointerMapper projections;

    public CfgStore(
            CfgDraftMapper drafts,
            CfgVersionMapper versions,
            CfgChangeMapper changes,
            CfgProjectionPointerMapper projections) {
        this.drafts = drafts;
        this.versions = versions;
        this.changes = changes;
        this.projections = projections;
    }

    public Optional<CfgDraftDO> findDraft(String objectType, String objectId) {
        return Optional.ofNullable(drafts.findOne(objectType, objectId));
    }

    public void insertDraft(CfgDraftDO row) {
        drafts.insert(row);
    }

    public boolean updateDraftOptimistic(CfgDraftDO row, long expectedRevision) {
        return drafts.updateOptimistic(row, expectedRevision) == 1;
    }

    public void insertVersion(CfgVersionDO row) {
        versions.insert(row);
    }

    public Optional<CfgVersionDO> findVersion(String objectType, String objectId, int versionNo) {
        return Optional.ofNullable(versions.findByNo(objectType, objectId, versionNo));
    }

    public Optional<CfgVersionDO> findVersionById(String versionId) {
        return Optional.ofNullable(versions.findById(versionId));
    }

    public Optional<Integer> maxVersionNo(String objectType, String objectId) {
        return Optional.ofNullable(versions.maxVersionNo(objectType, objectId));
    }

    public void insertChange(
            String objectType,
            String targetKey,
            String beforeJson,
            String afterJson,
            String actorId,
            String reason,
            String traceId) {
        changes.insert(objectType, targetKey, beforeJson, afterJson, actorId, reason, traceId);
    }

    public void updateTypeTablePointer(String objectType, String objectId, String versionId, int versionNo) {
        switch (objectType) {
            case "MODEL_PROVIDER", "model_provider" ->
                    projections.updateProvider(objectId, versionId, versionNo);
            case "MODEL_BINDING", "model_binding" ->
                    projections.updateBinding(objectId, versionId, versionNo);
            case "MODEL_ALIAS", "model_logical", "MODEL_LOGICAL" ->
                    projections.updateLogical(objectId, versionId, versionNo);
            case "MODEL_ROUTING_PLAN", "model_routing_plan" ->
                    projections.updateRoutingPlan(objectId, versionId, versionNo);
            default -> {
                // 通用类型仅走 Redis 指针；MH-08 再接投影表
            }
        }
    }
}
