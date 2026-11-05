package com.tioka.orchestration.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("orch_idem")
public class OrchIdemDO extends AppendOnlyDO {
    @TableId(type = IdType.INPUT)
    private String idempotencyKey;
    private String runId;

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
}
