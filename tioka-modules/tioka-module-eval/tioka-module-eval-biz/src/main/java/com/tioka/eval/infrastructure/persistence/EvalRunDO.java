package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * EVO-02 / V176：评测运行（ADR-031）。kind=BATCH 本批落地（ONLINE/EXPERIMENT_ARM/PUBLISH_GATE 预留）；
 * run_as_json 记发起身份（与 runtime_turn.run_as_json 同形，供 turn 以该身份重放）。
 * 审计仅 create_at（ADR-031 原文口径），挂 TechnicalRecordDO 空基类。
 */
@TableName("eval_run")
public class EvalRunDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    /** BATCH / ONLINE / EXPERIMENT_ARM / PUBLISH_GATE */
    private String kind;
    private String experimentId;
    private String datasetId;
    private Integer datasetVersion;
    /** AGENT_VERSION / FLOW_PUBLISH */
    private String subjectType;
    private String subjectRef;
    private String variablesJson;
    /** RUNNING / COMPLETED / FAILED */
    private String status;
    private String summaryJson;
    private String runAsJson;
    private LocalDateTime createAt;
    private LocalDateTime endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }
    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public Integer getDatasetVersion() { return datasetVersion; }
    public void setDatasetVersion(Integer datasetVersion) { this.datasetVersion = datasetVersion; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
    public String getSubjectRef() { return subjectRef; }
    public void setSubjectRef(String subjectRef) { this.subjectRef = subjectRef; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummaryJson() { return summaryJson; }
    public void setSummaryJson(String summaryJson) { this.summaryJson = summaryJson; }
    public String getRunAsJson() { return runAsJson; }
    public void setRunAsJson(String runAsJson) { this.runAsJson = runAsJson; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
