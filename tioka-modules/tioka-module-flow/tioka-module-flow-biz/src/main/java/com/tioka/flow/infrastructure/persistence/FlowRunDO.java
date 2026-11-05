package com.tioka.flow.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
@TableName("flow_run")
public class FlowRunDO extends BaseDO {
    @TableId(type = IdType.INPUT)
        private String id;
    private String versionId;
    private String status;
    private String traceJson;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTraceJson() { return traceJson; }
    public void setTraceJson(String traceJson) { this.traceJson = traceJson; }
}
