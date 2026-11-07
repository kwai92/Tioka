package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("kb_document")
public class KbDocumentDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String kbId;
    private String title;
    private String sourceType;
    private String status;
    private String format;
    private String errorMessage;
    /** V118：文档级 ACL JSON；M1 建列不启用（ADR-029 第 23 条）。 */
    private String aclPrincipals;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getAclPrincipals() { return aclPrincipals; }
    public void setAclPrincipals(String aclPrincipals) { this.aclPrincipals = aclPrincipals; }
}
