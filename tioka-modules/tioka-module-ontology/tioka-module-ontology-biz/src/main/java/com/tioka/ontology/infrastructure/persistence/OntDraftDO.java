package com.tioka.ontology.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.tioka.ontology.infrastructure.PgTextArrayTypeHandler;

import java.time.Instant;
import java.util.List;

/**
 * {@code ont_draft}（V172）：一份合同一行当前草稿（M3-03 §7）。
 * 实例级投影表：无 workspace_id 列（store_key 承载门店维度），登记 workspace-scope-exempt。
 */
@TableName(value = "ont_draft", autoResultMap = true)
public class OntDraftDO extends TechnicalRecordDO {

    @TableId
    private String instanceKey;
    private String defKey;
    private Integer defVersion;
    private String storeKey;

    @TableField(typeHandler = PgTextArrayTypeHandler.class)
    private List<String> docIds;

    /** JSONB 列，按 repo 惯例映射为 String（ConnectorInstanceDO.configJson 同口径）。 */
    private String modelJson;
    private String validationJson;
    private String buildRunId;
    private String bizDraftRef;
    /** 乐观锁（M3-03 §7 save(draft)）。 */
    private Integer revision;
    private Instant updatedAt;

    public String getInstanceKey() {
        return instanceKey;
    }

    public void setInstanceKey(String instanceKey) {
        this.instanceKey = instanceKey;
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

    public List<String> getDocIds() {
        return docIds;
    }

    public void setDocIds(List<String> docIds) {
        this.docIds = docIds;
    }

    public String getModelJson() {
        return modelJson;
    }

    public void setModelJson(String modelJson) {
        this.modelJson = modelJson;
    }

    public String getValidationJson() {
        return validationJson;
    }

    public void setValidationJson(String validationJson) {
        this.validationJson = validationJson;
    }

    public String getBuildRunId() {
        return buildRunId;
    }

    public void setBuildRunId(String buildRunId) {
        this.buildRunId = buildRunId;
    }

    public String getBizDraftRef() {
        return bizDraftRef;
    }

    public void setBizDraftRef(String bizDraftRef) {
        this.bizDraftRef = bizDraftRef;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
