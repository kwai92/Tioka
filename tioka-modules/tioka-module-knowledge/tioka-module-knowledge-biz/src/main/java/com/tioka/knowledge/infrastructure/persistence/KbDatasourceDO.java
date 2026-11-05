package com.tioka.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("kb_datasource")
public class KbDatasourceDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String kbId;
    private String type;
    private String uri;
    private String status;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getKbId() { return kbId; }
    public void setKbId(String kbId) { this.kbId = kbId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
