package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.LocalDateTime;

/** {@code trust_undo_circuit_policy}（V226 / TRU-08）：空间级熔断阈值。 */
@TableName("trust_undo_circuit_policy")
public class TrustUndoCircuitPolicyDO extends TechnicalRecordDO {

    @TableId
    private String workspaceId;
    private Integer undoThreshold;
    private Integer compFailThreshold;
    private Integer windowMinutes;
    private String updateBy;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createAt;
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateAt;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Integer getUndoThreshold() { return undoThreshold; }
    public void setUndoThreshold(Integer undoThreshold) { this.undoThreshold = undoThreshold; }
    public Integer getCompFailThreshold() { return compFailThreshold; }
    public void setCompFailThreshold(Integer compFailThreshold) { this.compFailThreshold = compFailThreshold; }
    public Integer getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(Integer windowMinutes) { this.windowMinutes = windowMinutes; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
    public LocalDateTime getCreateAt() { return createAt; }
    public void setCreateAt(LocalDateTime createAt) { this.createAt = createAt; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
