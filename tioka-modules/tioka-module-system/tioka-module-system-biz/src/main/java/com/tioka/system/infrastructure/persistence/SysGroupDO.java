package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * {@code sys_group}（V110 / FND-04）。无逻辑删；TIMESTAMPTZ 列用 {@link Instant}
 *（不可继承 MutableRecordDO 的 LocalDateTime，见 SysApiKeyDO）。
 */
@TableName("sys_group")
public class SysGroupDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String source;
    private String externalId;
    private Instant createAt;
    private Instant updateAt;
    private String createBy;
    private String updateBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public Instant getCreateAt() { return createAt; }
    public void setCreateAt(Instant createAt) { this.createAt = createAt; }
    public Instant getUpdateAt() { return updateAt; }
    public void setUpdateAt(Instant updateAt) { this.updateAt = updateAt; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }
}
