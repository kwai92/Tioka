package com.tioka.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import java.time.LocalDateTime;

@TableName("agent_draft")
public class AgentDraftDO {
    @TableId(type = IdType.INPUT)
    private String agentId;
    private String specJson;
    private Integer schemaVersion;
    @Version
    private Integer revision;
    private String baseVersionId;
    private LocalDateTime updateAt;
    private String updateBy;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getSpecJson() { return specJson; }
    public void setSpecJson(String specJson) { this.specJson = specJson; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getBaseVersionId() { return baseVersionId; }
    public void setBaseVersionId(String baseVersionId) { this.baseVersionId = baseVersionId; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
