package com.tioka.connector.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

@TableName("im_outbox")
public class ImOutboxDO extends AppendOnlyDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channel;
    private String payloadJson;
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
}
