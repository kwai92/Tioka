package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("orch_run")
public class OrchRunDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String status;
    private String eventId;
    private String workerId;
    private Long leaseUntil;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public Long getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Long leaseUntil) { this.leaseUntil = leaseUntil; }
}
