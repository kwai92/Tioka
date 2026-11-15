package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

import java.time.LocalDateTime;

/**
 * {@code evidence_pack}（V261 / OBS-12 / ADR-075 §5）：证据包登记行。
 *
 * <p>每次导出登记一条：范围、导出人、行数、包整体哈希与平台密钥签名，供事后审计与离线独立校验回溯。
 * {@code omittedSections} 记录因权限被省略的段（JSON 数组文本）。
 * {@code exportedAt} 按 UTC {@code LocalDateTime} 读写（ENG-11 / {@code UtcLocalDateTimeTypeHandler}）。
 */
@TableName("evidence_pack")
public class EvidencePackDO extends BaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String scopeType;
    private String scopeRef;
    private String workspaceId;
    private String exportedBy;
    private LocalDateTime exportedAt;
    private String clearance;
    private Boolean includeConfidential;
    private String omittedSections;
    private Integer fileCount;
    private Integer rowCount;
    private String packageHash;
    private String signatureAlg;
    private String keyId;
    private String signature;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getScopeRef() { return scopeRef; }
    public void setScopeRef(String scopeRef) { this.scopeRef = scopeRef; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getExportedBy() { return exportedBy; }
    public void setExportedBy(String exportedBy) { this.exportedBy = exportedBy; }
    public LocalDateTime getExportedAt() { return exportedAt; }
    public void setExportedAt(LocalDateTime exportedAt) { this.exportedAt = exportedAt; }
    public String getClearance() { return clearance; }
    public void setClearance(String clearance) { this.clearance = clearance; }
    public Boolean getIncludeConfidential() { return includeConfidential; }
    public void setIncludeConfidential(Boolean includeConfidential) { this.includeConfidential = includeConfidential; }
    public String getOmittedSections() { return omittedSections; }
    public void setOmittedSections(String omittedSections) { this.omittedSections = omittedSections; }
    public Integer getFileCount() { return fileCount; }
    public void setFileCount(Integer fileCount) { this.fileCount = fileCount; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
    public String getPackageHash() { return packageHash; }
    public void setPackageHash(String packageHash) { this.packageHash = packageHash; }
    public String getSignatureAlg() { return signatureAlg; }
    public void setSignatureAlg(String signatureAlg) { this.signatureAlg = signatureAlg; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
