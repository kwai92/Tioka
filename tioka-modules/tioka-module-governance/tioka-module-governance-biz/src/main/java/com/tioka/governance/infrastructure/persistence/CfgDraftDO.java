package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** cfg_draft 行（ADR-041 §2 / V150）。复合主键，不套 BaseDO。 */
@TableName("cfg_draft")
public class CfgDraftDO {
    private String objectType;
    private String objectId;
    private String spec;
    private Long revision;
    private Long basedOnVersion;
    private LocalDateTime updateAt;
    private String updateBy;

    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public Long getRevision() { return revision; }
    public void setRevision(Long revision) { this.revision = revision; }
    public Long getBasedOnVersion() { return basedOnVersion; }
    public void setBasedOnVersion(Long basedOnVersion) { this.basedOnVersion = basedOnVersion; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
