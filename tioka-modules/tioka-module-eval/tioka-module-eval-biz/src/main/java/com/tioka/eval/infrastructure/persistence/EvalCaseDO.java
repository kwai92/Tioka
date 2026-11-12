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
    /** 材料单元键（C-18 EVO-07 / V225；NULL = 未声明） */
    private String unitKey;
    /** VALID / STALE / DISPUTED（ADR-068 第 2 节；非 VALID 不参与门禁统计） */
    private String labelStatus;
    /** 逐条必须通过（回归集缺省为是） */
    private Boolean mustPass;
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
    public String getUnitKey() { return unitKey; }
    public void setUnitKey(String unitKey) { this.unitKey = unitKey; }
    public String getLabelStatus() { return labelStatus; }
    public void setLabelStatus(String labelStatus) { this.labelStatus = labelStatus; }
    public Boolean getMustPass() { return mustPass; }
    public void setMustPass(Boolean mustPass) { this.mustPass = mustPass; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
