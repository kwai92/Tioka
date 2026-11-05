package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("orch_why")
public class OrchWhyDO extends AppendOnlyDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String atTs;
    private String kind;
    private String detail;
    private Integer ord;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getAtTs() { return atTs; }
    public void setAtTs(String atTs) { this.atTs = atTs; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
