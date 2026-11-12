package com.tioka.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.tioka.framework.persistence.WorkspaceAwareDO;

import java.time.LocalDateTime;

@TableName("agent_agent")
public class AgentDO extends WorkspaceAwareDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private Boolean enabled;
    private String publishedVersionId;
    @Version
    private Integer version;
    private String visibility;
    /** 授权书生命周期状态（TRU-03 / V201；ACTIVE / SUSPENDED / HANDOVER，默认 ACTIVE）。 */
    private String charterStatus;
    /** 授权书最近确认时间（TRU-04 复核起点；交接确认时刷新）。 */
    private LocalDateTime reviewedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getPublishedVersionId() { return publishedVersionId; }
    public void setPublishedVersionId(String publishedVersionId) { this.publishedVersionId = publishedVersionId; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public String getCharterStatus() { return charterStatus; }
    public void setCharterStatus(String charterStatus) { this.charterStatus = charterStatus; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
