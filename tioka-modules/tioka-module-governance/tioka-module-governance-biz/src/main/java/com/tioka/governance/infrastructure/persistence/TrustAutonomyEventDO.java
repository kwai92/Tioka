package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code trust_autonomy_event}（V203 / ADR-034「数据模型」）；只增。
 * kind：PROMOTE / DEMOTE / INCIDENT / RESET / SAMPLING（M3 抽检记录复用本表，登记偏差）。
 */
@TableName("trust_autonomy_event")
public class TrustAutonomyEventDO extends TechnicalRecordDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String agentId;
    private String actionClass;
    private String kind;
    private String fromLevel;
    private String toLevel;
    private String reason;
    private String evidenceJson;
    private String actor;
    private LocalDateTime createAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getActionClass() { return actionClass; }
    public void setActionClass(String actionClass) { this.actionClass = actionClass; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFromLevel() { return fromLevel; }
    public void setFromLevel(String fromLevel) { this.fromLevel = fromLevel; }
    public String getToLevel() { return toLevel; }
    public void setToLevel(String toLevel) { this.toLevel = toLevel; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
}
