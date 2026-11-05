package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("automation_job")
public class AutomationJobDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String jobType;
    private String status;
    private String payloadJson;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
