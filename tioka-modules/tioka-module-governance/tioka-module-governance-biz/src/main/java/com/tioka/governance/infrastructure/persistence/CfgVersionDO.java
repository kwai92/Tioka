package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** cfg_version 行（ADR-041 §2 / V150）；写入后不可更新。 */
@TableName("cfg_version")
public class CfgVersionDO {
    @TableId(type = IdType.INPUT)
    private String versionId;
    private String objectType;
    private String objectId;
    private Integer versionNo;
    private String spec;
    private String contentHash;
    private String status;
    private String publishNote;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String previousVersionId;

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPublishNote() { return publishNote; }
    public void setPublishNote(String publishNote) { this.publishNote = publishNote; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public String getPreviousVersionId() { return previousVersionId; }
    public void setPreviousVersionId(String previousVersionId) { this.previousVersionId = previousVersionId; }
}
