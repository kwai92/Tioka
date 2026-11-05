package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("sys_quota")
public class SysQuotaDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String workspaceId;
    private Integer tokenLimit;
    private Integer concurrentLimit;
    private Integer tokenUsed;
    private Integer concurrentUsed;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Integer getTokenLimit() { return tokenLimit; }
    public void setTokenLimit(Integer tokenLimit) { this.tokenLimit = tokenLimit; }
    public Integer getConcurrentLimit() { return concurrentLimit; }
    public void setConcurrentLimit(Integer concurrentLimit) { this.concurrentLimit = concurrentLimit; }
    public Integer getTokenUsed() { return tokenUsed; }
    public void setTokenUsed(Integer tokenUsed) { this.tokenUsed = tokenUsed; }
    public Integer getConcurrentUsed() { return concurrentUsed; }
    public void setConcurrentUsed(Integer concurrentUsed) { this.concurrentUsed = concurrentUsed; }
}
