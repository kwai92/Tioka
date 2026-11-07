package com.tioka.infra.messaging.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.Instant;

/**
 * {@code sys_consumed}（V130）。复合主键 (consumer, event_id)；写入走 XML insertIgnore。
 */
@TableName("sys_consumed")
public class SysConsumedDO extends TechnicalRecordDO {

    /** 复合主键之一；MP 单字段 {@code @TableId} 标记便于元数据识别。 */
    @TableId(type = IdType.INPUT)
    private String eventId;
    private String consumer;
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
