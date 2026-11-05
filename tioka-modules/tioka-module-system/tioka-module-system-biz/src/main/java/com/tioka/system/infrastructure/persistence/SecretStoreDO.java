package com.tioka.system.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

@TableName("secret_store")
public class SecretStoreDO extends BaseDO {
    private String ns;
    private String secretKey;
    private String cipherText;

    public String getNs() { return ns; }
    public void setNs(String ns) { this.ns = ns; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getCipherText() { return cipherText; }
    public void setCipherText(String cipherText) { this.cipherText = cipherText; }
}
