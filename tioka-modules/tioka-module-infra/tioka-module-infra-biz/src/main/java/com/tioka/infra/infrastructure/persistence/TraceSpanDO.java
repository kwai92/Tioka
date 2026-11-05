package com.tioka.infra.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("trace_span")
public class TraceSpanDO extends AppendOnlyDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String atTs;
    private String kind;
    private String detail;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getAtTs() { return atTs; }
    public void setAtTs(String atTs) { this.atTs = atTs; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
