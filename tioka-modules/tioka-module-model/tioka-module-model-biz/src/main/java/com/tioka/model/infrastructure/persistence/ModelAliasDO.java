package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * 模型别名（MH-08 / ADR-041 §1：由 LogicalModel 改名扩展；V151 重命名 model_logical → model_alias）。
 * 用途 + 指向（一个路由计划或一个 binding）；sys.* 前缀为系统别名。
 */
@TableName("model_alias")
public class ModelAliasDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String capabilityTags;
    /** ModelPurpose 名；sys.* 必填，业务别名可空。 */
    private String purpose;
    /** routing_plan | binding；暂无指向时可空。 */
    private String targetType;
    private String targetId;
    /** GOV-12（V150）：当前生效版本指针。 */
    private String currentVersionId;
    private Integer currentVersionNo;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCapabilityTags() { return capabilityTags; }
    public void setCapabilityTags(String capabilityTags) { this.capabilityTags = capabilityTags; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(String currentVersionId) { this.currentVersionId = currentVersionId; }
    public Integer getCurrentVersionNo() { return currentVersionNo; }
    public void setCurrentVersionNo(Integer currentVersionNo) { this.currentVersionNo = currentVersionNo; }
}
