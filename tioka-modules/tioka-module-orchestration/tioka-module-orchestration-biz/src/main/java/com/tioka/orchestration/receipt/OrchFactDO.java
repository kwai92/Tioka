package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/**
 * {@code orch_fact}（V132）。放在 receipt 包内，避免越界改 infrastructure（PK-13 可改范围）。
 */
@TableName("orch_fact")
public class OrchFactDO extends TechnicalRecordDO {
    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;
    private Integer seq;
    private String kind;
    private String detailJson;
    private LocalDateTime at;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
}
