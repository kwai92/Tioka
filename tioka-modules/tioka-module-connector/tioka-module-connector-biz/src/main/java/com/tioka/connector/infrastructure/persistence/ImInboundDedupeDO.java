package com.tioka.connector.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 入站事件去重（V140 {@code im_inbound_dedupe}）。复合主键 (binding_id, event_id)。
 */
@TableName("im_inbound_dedupe")
public class ImInboundDedupeDO {

    private String bindingId;
    private String eventId;
    private LocalDateTime receivedAt;

    public String getBindingId() {
        return bindingId;
    }

    public void setBindingId(String bindingId) {
        this.bindingId = bindingId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }
}
