package com.tioka.connector.infrastructure.persistence;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;
@TableName("feishu_doc_push")
public class FeishuDocPushDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String remote;
    private String content;
    public String getRemote() { return remote; }
    public void setRemote(String remote) { this.remote = remote; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
