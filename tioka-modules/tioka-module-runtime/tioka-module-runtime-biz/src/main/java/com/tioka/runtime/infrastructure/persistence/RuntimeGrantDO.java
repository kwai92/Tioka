package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * MAS-01 委派授予行（ADR-039 数据模型 / V160）。会话级内部对象，无 workspace 列。
 */
@TableName("rt_grant")
public class RuntimeGrantDO extends com.tioka.framework.persistence.TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String parentId;
    private String rootPrincipal;
    private String onBehalfOf;
    private String chainJson;
    private Integer depth;
    private String bodyJson;
    private String hash;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createAt;
    private LocalDateTime endedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getRootPrincipal() { return rootPrincipal; }
    public void setRootPrincipal(String rootPrincipal) { this.rootPrincipal = rootPrincipal; }
    public String getOnBehalfOf() { return onBehalfOf; }
    public void setOnBehalfOf(String onBehalfOf) { this.onBehalfOf = onBehalfOf; }
    public String getChainJson() { return chainJson; }
    public void setChainJson(String chainJson) { this.chainJson = chainJson; }
    public Integer getDepth() { return depth; }
    public void setDepth(Integer depth) { this.depth = depth; }
    public String getBodyJson() { return bodyJson; }
    public void setBodyJson(String bodyJson) { this.bodyJson = bodyJson; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(LocalDateTime endedAt) { this.endedAt = endedAt; }
}
