package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * EVO-02 / V176：评测用例（ADR-031）。按 (dataset_id, version) 版本化；
 * 无 workspace_id（随数据集归属，已登记 workspace-scope-exempt）；只增不改。
 * 审计仅 create_at（ADR-031 原文口径），挂 TechnicalRecordDO 空基类，
 * create_at 依赖 DB DEFAULT now()。
 */
@TableName("eval_case")
public class EvalCaseDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String datasetId;
    private Integer version;
    private String inputJson;
    private String expectedJson;
    /** MANUAL / IMPORT / ONLINE */
    private String source;
    private String sourceTurnId;
    private LocalDateTime createAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }
    public String getExpectedJson() { return expectedJson; }
    public void setExpectedJson(String expectedJson) { this.expectedJson = expectedJson; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceTurnId() { return sourceTurnId; }
    public void setSourceTurnId(String sourceTurnId) { this.sourceTurnId = sourceTurnId; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
