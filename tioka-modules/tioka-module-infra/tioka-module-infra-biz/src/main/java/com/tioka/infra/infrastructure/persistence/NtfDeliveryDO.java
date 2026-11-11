package com.tioka.infra.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.Instant;

/**
 * {@code ntf_delivery}（V174 / NTF-01）。平台通知投递状态机：无 soft-delete / workspace，
 * 幂等键 {@code dedup_key = {noticeKey}:{channel}:{recipient}}。
 */
@TableName("ntf_delivery")
public class NtfDeliveryDO extends TechnicalRecordDO {

    @TableId
    private String deliveryId;
    private String dedupKey;
    private String channel;
    private String recipient;
    private String noticeJson;
    private String status;
    private String externalRef;
    private Integer attempts;
    private String lastError;
    private Instant createdAt;
    private Instant sentAt;

    public String getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(String deliveryId) {
        this.deliveryId = deliveryId;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getNoticeJson() {
        return noticeJson;
    }

    public void setNoticeJson(String noticeJson) {
        this.noticeJson = noticeJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void setAttempts(Integer attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
