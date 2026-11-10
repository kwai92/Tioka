package com.tioka.ontology.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.tioka.ontology.infrastructure.PgTextArrayTypeHandler;

import java.time.Instant;
import java.util.List;

/**
 * {@code ont_instance_version}（V172）：已确认版本只读投影（ADR-029 第 50 条：以业务系统为准）。
 * 联合主键 (instance_key, version)——复合主键不走 @TableId，增删查走 mapper XML；
 * 无 workspace_id 列，登记 workspace-scope-exempt。
 */
@TableName(value = "ont_instance_version", autoResultMap = true)
public class OntInstanceVersionDO extends TechnicalRecordDO {

    private String instanceKey;
    private Integer version;
    /** CONFIRMED / SUPERSEDED。 */
    private String status;
    private String defKey;
    private Integer defVersion;
    private String storeKey;
    private String modelJson;
    private String contentHash;
    private String diffPrev;
    private String diffDraft;
    private String refsJson;
    private String confirmedBy;
    private Instant confirmedAt;

    @TableField(typeHandler = PgTextArrayTypeHandler.class)
    private List<String> aclPrincipals;

    /** CONFIDENTIAL 缺省（V172）。 */
    private String dataLabel;

    public String getInstanceKey() {
        return instanceKey;
    }

    public void setInstanceKey(String instanceKey) {
        this.instanceKey = instanceKey;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDefKey() {
        return defKey;
    }

    public void setDefKey(String defKey) {
        this.defKey = defKey;
    }

    public Integer getDefVersion() {
        return defVersion;
    }

    public void setDefVersion(Integer defVersion) {
        this.defVersion = defVersion;
    }

    public String getStoreKey() {
        return storeKey;
    }

    public void setStoreKey(String storeKey) {
        this.storeKey = storeKey;
    }

    public String getModelJson() {
        return modelJson;
    }

    public void setModelJson(String modelJson) {
        this.modelJson = modelJson;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getDiffPrev() {
        return diffPrev;
    }

    public void setDiffPrev(String diffPrev) {
        this.diffPrev = diffPrev;
    }

    public String getDiffDraft() {
        return diffDraft;
    }

    public void setDiffDraft(String diffDraft) {
        this.diffDraft = diffDraft;
    }

    public String getRefsJson() {
        return refsJson;
    }

    public void setRefsJson(String refsJson) {
        this.refsJson = refsJson;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public List<String> getAclPrincipals() {
        return aclPrincipals;
    }

    public void setAclPrincipals(List<String> aclPrincipals) {
        this.aclPrincipals = aclPrincipals;
    }

    public String getDataLabel() {
        return dataLabel;
    }

    public void setDataLabel(String dataLabel) {
        this.dataLabel = dataLabel;
    }
}
