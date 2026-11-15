package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.persistence.EvidencePackDO;

import java.util.List;

/** 证据包登记读写（OBS-12 / ADR-075 §5）。 */
public interface EvidencePackRepository {

    /** 登记一次导出。 */
    void save(EvidencePackDO row);

    /**
     * 按范围取登记（可选限定空间），按导出时间倒序。
     *
     * @param workspaceId 为 {@code null} / 空表示不限空间（平台级调用方）
     */
    List<EvidencePackDO> listByScope(String scopeType, String scopeRef, String workspaceId, int limit);
}
