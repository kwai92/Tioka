package com.tioka.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "tioka.audit")
public class AuditProperties {
    /** 保留天数；已转发且超期的行每日清理。默认 365。 */
    private int retentionDays = 365;
    /** sink：file / noop */
    private String sink = "file";
    /** 转发批大小。 */
    private int forwardBatchSize = 100;
    /** 文件 sink 相对 {@code tioka.home} 的路径。 */
    private String fileRelativePath = "audit/audit.jsonl";
    /**
     * 可信反向代理（精确 IP 或 CIDR）。空列表 = 永不信任 {@code X-Forwarded-For}（OBS-01）。
     */
    private List<String> trustedProxies = new ArrayList<>();
    /** 是否允许物理删除已转发审计（默认 false；GOV-09 前禁止打开）。 */
    private boolean physicalPurgeEnabled = false;
    /** 工具审计降级文件相对 {@code tioka.home} 的路径。 */
    private String degradedRelativePath = "audit/audit-degraded.jsonl";

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getSink() {
        return sink;
    }

    public void setSink(String sink) {
        this.sink = sink;
    }

    public int getForwardBatchSize() {
        return forwardBatchSize;
    }

    public void setForwardBatchSize(int forwardBatchSize) {
        this.forwardBatchSize = forwardBatchSize;
    }

    public String getFileRelativePath() {
        return fileRelativePath;
    }

    public void setFileRelativePath(String fileRelativePath) {
        this.fileRelativePath = fileRelativePath;
    }

    public List<String> getTrustedProxies() {
        return trustedProxies;
    }

    public void setTrustedProxies(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies == null ? new ArrayList<>() : trustedProxies;
    }

    public boolean isPhysicalPurgeEnabled() {
        return physicalPurgeEnabled;
    }

    public void setPhysicalPurgeEnabled(boolean physicalPurgeEnabled) {
        this.physicalPurgeEnabled = physicalPurgeEnabled;
    }

    public String getDegradedRelativePath() {
        return degradedRelativePath;
    }

    public void setDegradedRelativePath(String degradedRelativePath) {
        this.degradedRelativePath = degradedRelativePath;
    }
}
