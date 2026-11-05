package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("scim_user")
public class ScimUserDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String attrsJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAttrsJson() { return attrsJson; }
    public void setAttrsJson(String attrsJson) { this.attrsJson = attrsJson; }
}
