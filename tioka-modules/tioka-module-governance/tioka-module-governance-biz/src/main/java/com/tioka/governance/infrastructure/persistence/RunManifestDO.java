package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

/**
 * {@code run_manifest}（V228 / ADR-075 §3 运行清单）：Turn / 编排 Run / Flow Run 开始时一条，
 * 续跑且内容有变化时追加（{@code seq} 递增）。
 *
 * <p>{@code manifestJson} 为 ADR-075 §3 全量内容（Agent 版本、授权书、Cedar 策略集、护栏 / 数据集策略、
 * 别名与路由计划、要点规则包与 Schema 版本、关联键）的 JSON 文本；{@code contentHash} 为其规范化哈希，
 * 是同类运行的差异对比键。
 *
 * <p>{@code prevHash} / {@code hash} 预留给 ADR-072 哈希链封印（GOV-09 链服务扩展另行接入）。
 */
@TableName("run_manifest")
public class RunManifestDO extends AppendOnlyDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String runKind;
    private String runId;
    private Integer seq;
    private String requestId;
    private String parentRequestId;
    private String workspaceId;
    private String subject;
    private String traceId;
    private String agentVersionId;
    private String specHash;
    private String grantId;
    private String manifestJson;
    private String contentHash;
    private String prevHash;
    private String hash;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunKind() { return runKind; }
    public void setRunKind(String runKind) { this.runKind = runKind; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getParentRequestId() { return parentRequestId; }
    public void setParentRequestId(String parentRequestId) { this.parentRequestId = parentRequestId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getAgentVersionId() { return agentVersionId; }
    public void setAgentVersionId(String agentVersionId) { this.agentVersionId = agentVersionId; }
    public String getSpecHash() { return specHash; }
    public void setSpecHash(String specHash) { this.specHash = specHash; }
    public String getGrantId() { return grantId; }
    public void setGrantId(String grantId) { this.grantId = grantId; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }
}
