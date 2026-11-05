package com.tioka.agent.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
@TableName("agent_marketplace")
public class AgentMarketplaceDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String assetType;
    private String assetId;
    private String status;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAssetType() { return assetType; }
    public void setAssetType(String assetType) { this.assetType = assetType; }
    public String getAssetId() { return assetId; }
    public void setAssetId(String assetId) { this.assetId = assetId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
