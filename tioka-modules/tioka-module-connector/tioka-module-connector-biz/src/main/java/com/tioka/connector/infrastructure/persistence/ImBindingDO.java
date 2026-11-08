package com.tioka.connector.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * IM 渠道绑定（V140 {@code im_binding}）。无 owner_id，不继承 WorkspaceAwareDO。
 */
@TableName("im_binding")
public class ImBindingDO extends BaseDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String channel;
    private String appId;
    private String secretRef;
    private String encryptKeyRef;
    private String verificationTokenRef;
    private String cardSignKeyRef;
    private String defaultAgentId;
    private Boolean orchEnabled;
    private String identityMatch;
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getSecretRef() {
        return secretRef;
    }

    public void setSecretRef(String secretRef) {
        this.secretRef = secretRef;
    }

    public String getEncryptKeyRef() {
        return encryptKeyRef;
    }

    public void setEncryptKeyRef(String encryptKeyRef) {
        this.encryptKeyRef = encryptKeyRef;
    }

    public String getVerificationTokenRef() {
        return verificationTokenRef;
    }

    public void setVerificationTokenRef(String verificationTokenRef) {
        this.verificationTokenRef = verificationTokenRef;
    }

    public String getCardSignKeyRef() {
        return cardSignKeyRef;
    }

    public void setCardSignKeyRef(String cardSignKeyRef) {
        this.cardSignKeyRef = cardSignKeyRef;
    }

    public String getDefaultAgentId() {
        return defaultAgentId;
    }

    public void setDefaultAgentId(String defaultAgentId) {
        this.defaultAgentId = defaultAgentId;
    }

    public Boolean getOrchEnabled() {
        return orchEnabled;
    }

    public void setOrchEnabled(Boolean orchEnabled) {
        this.orchEnabled = orchEnabled;
    }

    public String getIdentityMatch() {
        return identityMatch;
    }

    public void setIdentityMatch(String identityMatch) {
        this.identityMatch = identityMatch;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
