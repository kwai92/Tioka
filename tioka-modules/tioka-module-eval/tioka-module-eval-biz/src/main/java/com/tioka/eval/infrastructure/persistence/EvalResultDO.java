package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * EVO-02 / V176：评测逐条结果（ADR-031）。只增不改；无 workspace_id（随 run 归属，
 * 已登记 workspace-scope-exempt）；idx_eval_result_turn 支撑轨迹视图按 Turn 取分。
 * 审计仅 create_at（ADR-031 原文口径），挂 TechnicalRecordDO 空基类。
 */
@TableName("eval_result")
public class EvalResultDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String turnId;
    private String caseId;
    private String evaluatorId;
    private Double score;
    private Boolean passed;
    private String reason;
    /** 运行序号 1..trials（C-18 EVO-07 / V225；缺省 1） */
    private Integer trial;
    private LocalDateTime createAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getCaseId() { return caseId; }
    public void setCaseId(String caseId) { this.caseId = caseId; }
    public String getEvaluatorId() { return evaluatorId; }
    public void setEvaluatorId(String evaluatorId) { this.evaluatorId = evaluatorId; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Boolean getPassed() { return passed; }
    public void setPassed(Boolean passed) { this.passed = passed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getTrial() { return trial; }
    public void setTrial(Integer trial) { this.trial = trial; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
