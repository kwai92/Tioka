package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * KNW-05 / V211：入库任务（ADR-018 五阶段：PARSE → CHUNK → EMBED → SWAP）。
 */
@TableName("kb_ingest_job")
public class KbIngestJobDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String docId;
    private Integer docVersion;
    /** PARSE / CHUNK / EMBED / SWAP */
    private String stage;
    /** QUEUED / RUNNING / DONE / FAILED */
    private String status;
    private Integer attempts;
    private String runAsJson;
    private String error;
    private java.time.LocalDateTime leaseUntil;
    /** UPLOAD / TEXT / CHANGE_EVENT（F39） */
    private String sourceType;
    /** 数据源 ID 等来源引用（F39） */
    private String sourceRef;
    /** F39：源权限主体，可选 */
    private String aclPrincipalsJson;
    /** 提交载荷（文本 / 上传字节 base64 + 文件名 / 事件内容） */
    private String payloadJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public Integer getDocVersion() { return docVersion; }
    public void setDocVersion(Integer docVersion) { this.docVersion = docVersion; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer attempts) { this.attempts = attempts; }
    public String getRunAsJson() { return runAsJson; }
    public void setRunAsJson(String runAsJson) { this.runAsJson = runAsJson; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public java.time.LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(java.time.LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
    public String getAclPrincipalsJson() { return aclPrincipalsJson; }
    public void setAclPrincipalsJson(String aclPrincipalsJson) { this.aclPrincipalsJson = aclPrincipalsJson; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
