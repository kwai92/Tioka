package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * {@code trust_undo_circuit}（V202 / TRU-07+08 共用）：撤销熔断计数。
 * 同 Agent 1h 内撤销 3 次或补偿失败 1 次 → 全类别降 L1（降级联动归 TRU-10，登记）。
 */
@TableName("trust_undo_circuit")
public class TrustUndoCircuitDO extends TechnicalRecordDO {

    @TableId
    private String agentId;
    private String workspaceId;
    private Integer undoCount;
    private Integer compFailCount;
    private LocalDateTime windowStart;

    /** V226 熔断开启状态：只由 TrustUndoCircuitMapper.xml 的 tryOpen 写入（CAS 去重）。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime openedAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String openReason;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateAt;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Integer getUndoCount() { return undoCount; }
    public void setUndoCount(Integer undoCount) { this.undoCount = undoCount; }
    public Integer getCompFailCount() { return compFailCount; }
    public void setCompFailCount(Integer compFailCount) { this.compFailCount = compFailCount; }
    public LocalDateTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalDateTime windowStart) { this.windowStart = windowStart; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }
    public String getOpenReason() { return openReason; }
    public void setOpenReason(String openReason) { this.openReason = openReason; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
