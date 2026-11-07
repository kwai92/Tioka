package com.tioka.infra.messaging.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** {@code sys_consumed}（V130）。 */
@TableName("sys_consumed")
public class SysConsumedDO {

    private String consumer;
    private String eventId;
    private Instant consumedAt;

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}
