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
    /** DAT-02（V164）：文档数据标签；null = 继承知识库标签。 */
    private String dataLabel;
    /** KNW-05 / V211：数据源 ID（F39 ChangeEvent 来源）。 */
    private String datasourceId;
    /** KNW-05 / V211：源系统文档标识。 */
    private String externalId;
    /** KNW-05 / V211：源系统版本号。 */
    private String externalVersion;
    /** KNW-05 / V211：内容哈希（SHA-256），去重跳过内容未变的重复入库。 */
    private String contentHash;
    /** KNW-05 / V211：当前生效版本（SWAP 单事务原子切换，检索只看该版本）。 */
    private Integer activeVersion;

    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getExternalVersion() { return externalVersion; }
    public void setExternalVersion(String externalVersion) { this.externalVersion = externalVersion; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public Integer getActiveVersion() { return activeVersion; }
    public void setActiveVersion(Integer activeVersion) { this.activeVersion = activeVersion; }

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
    public String getDataLabel() { return dataLabel; }
    public void setDataLabel(String dataLabel) { this.dataLabel = dataLabel; }
    public String getAclPrincipals() { return aclPrincipals; }
    public void setAclPrincipals(String aclPrincipals) { this.aclPrincipals = aclPrincipals; }
}
