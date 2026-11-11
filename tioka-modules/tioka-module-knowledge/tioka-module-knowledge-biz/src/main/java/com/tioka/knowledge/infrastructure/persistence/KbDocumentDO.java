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
    /** KNW-11 / V179：解析产物溯源（DOC 域共用服务 parseId）。 */
    private String parseId;
    /** KNW-11 / V179：解析方案键（docparse 契约，如 tioka.parse/v1）。 */
    private String parsePlan;
    /** KNW-11 / V179：解析器版本（去重键成分之一）。 */
    private String parsePlanVersion;
    /** KNW-11 / V179：解析状态（READY / PARTIAL / FAILED）。 */
    private String parseStatus;
    /** KNW-11 / V179：解析质量指标 JSON（页数 / 语言 / 块数等）。 */
    private String qualityMetricsJson;
    /** KNW-11 / V179：解析警告 JSON 数组（PARTIAL 的失败页与降级原因）。 */
    private String parseWarnings;

    public String getParseId() { return parseId; }
    public void setParseId(String parseId) { this.parseId = parseId; }
    public String getParsePlan() { return parsePlan; }
    public void setParsePlan(String parsePlan) { this.parsePlan = parsePlan; }
    public String getParsePlanVersion() { return parsePlanVersion; }
    public void setParsePlanVersion(String parsePlanVersion) { this.parsePlanVersion = parsePlanVersion; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public String getQualityMetricsJson() { return qualityMetricsJson; }
    public void setQualityMetricsJson(String qualityMetricsJson) { this.qualityMetricsJson = qualityMetricsJson; }
    public String getParseWarnings() { return parseWarnings; }
    public void setParseWarnings(String parseWarnings) { this.parseWarnings = parseWarnings; }

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
