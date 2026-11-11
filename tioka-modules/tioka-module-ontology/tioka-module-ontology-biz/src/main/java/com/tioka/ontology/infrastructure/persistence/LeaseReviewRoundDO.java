package com.tioka.ontology.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

/**
 * {@code lease_review_round}（V175，M3-04 LSE-06）：租赁审查轮次。
 * Tioka 侧只做幂等与重审判断；结论本身（lease.finding@1）只存业务系统。
 * 复合主键 (review_run_id, round)；无 workspace_id 列，登记 workspace-scope-exempt。
 */
@TableName(value = "lease_review_round")
public class LeaseReviewRoundDO extends TechnicalRecordDO {

    @TableId
    private String reviewRunId;
    /** 复合主键第二列：轮次，首轮 1，局部重审 +1。 */
    private Integer round;
    /** 关联编排 Run（可空：首轮自动触发前）。 */
    private String tiokaRunId;
    /** 审查所用模型版本。 */
    private String modelVersion;
    /** 要点库版本（新版本发布不自动重审）。 */
    private String checkpointSetVersion;
    /** RUNNING / COMPLETED / FAILED / SUPERSEDED。 */
    private String status;
    /** 统计 JSONB 列，按 repo 惯例映射为 String。 */
    private String statsJson;

    public String getReviewRunId() {
        return reviewRunId;
    }

    public void setReviewRunId(String reviewRunId) {
        this.reviewRunId = reviewRunId;
    }

    public Integer getRound() {
        return round;
    }

    public void setRound(Integer round) {
        this.round = round;
    }

    public String getTiokaRunId() {
        return tiokaRunId;
    }

    public void setTiokaRunId(String tiokaRunId) {
        this.tiokaRunId = tiokaRunId;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getCheckpointSetVersion() {
        return checkpointSetVersion;
    }

    public void setCheckpointSetVersion(String checkpointSetVersion) {
        this.checkpointSetVersion = checkpointSetVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatsJson() {
        return statsJson;
    }

    public void setStatsJson(String statsJson) {
        this.statsJson = statsJson;
    }
}
