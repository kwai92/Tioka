package com.tioka.skill.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
@TableName("skill_package")
public class SkillPackageDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private Boolean published;
    private String skillMd;
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getSkillMd() { return skillMd; }
    public void setSkillMd(String skillMd) { this.skillMd = skillMd; }
}
