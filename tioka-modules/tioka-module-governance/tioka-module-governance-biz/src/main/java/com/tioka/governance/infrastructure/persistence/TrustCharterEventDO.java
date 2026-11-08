package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

/** {@code trust_charter_event}（V201 / ADR-032）；只增。 */
@TableName("trust_charter_event")
public class TrustCharterEventDO extends AppendOnlyDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String agentId;
    private String kind;
    private String reason;
    private String fromOwner;
    private String toOwner;
    private String actor;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getFromOwner() { return fromOwner; }
    public void setFromOwner(String fromOwner) { this.fromOwner = fromOwner; }
    public String getToOwner() { return toOwner; }
    public void setToOwner(String toOwner) { this.toOwner = toOwner; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
}
