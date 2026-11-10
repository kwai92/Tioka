package com.tioka.runtime.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** {@code trust_undo_window}（V202 / TRU-07）：撤销期空间默认值。 */
@TableName("trust_undo_window")
public class TrustUndoWindowDO extends TechnicalRecordDO {

    @TableId
    private String workspaceId;
    private Integer windowHours;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updateAt;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public Integer getWindowHours() { return windowHours; }
    public void setWindowHours(Integer windowHours) { this.windowHours = windowHours; }
    public LocalDateTime getUpdateAt() { return updateAt; }
    public void setUpdateAt(LocalDateTime updateAt) { this.updateAt = updateAt; }
}
