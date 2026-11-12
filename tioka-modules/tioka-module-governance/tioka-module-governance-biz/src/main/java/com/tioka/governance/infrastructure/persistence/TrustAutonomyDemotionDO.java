package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

/** {@code trust_autonomy_demotion}（V226 / TRU-10）：降级事件幂等台账。 */
@TableName("trust_autonomy_demotion")
public class TrustAutonomyDemotionDO extends TechnicalRecordDO {

    @TableId
    private String demotionKey;
    private String workspaceId;
    private String agentId;
    private String actionClass;
    private String eventId;
    private String fromLevel;
    private String toLevel;

    public String getDemotionKey() { return demotionKey; }
    public void setDemotionKey(String demotionKey) { this.demotionKey = demotionKey; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getActionClass() { return actionClass; }
    public void setActionClass(String actionClass) { this.actionClass = actionClass; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getFromLevel() { return fromLevel; }
    public void setFromLevel(String fromLevel) { this.fromLevel = fromLevel; }
    public String getToLevel() { return toLevel; }
    public void setToLevel(String toLevel) { this.toLevel = toLevel; }
}
