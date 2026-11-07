package com.tioka.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
