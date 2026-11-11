package com.tioka.infra.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.Instant;

/**
 * {@code sys_external_identity}（V140 / FND-09 外部身份映射）。
 * 复合主键 (source, external_id)；NTF 收件人解析按 user_id 反查外部 ID。
 */
@TableName("sys_external_identity")
public class SysExternalIdentityDO extends TechnicalRecordDO {

    private String source;
    private String externalId;
    private String unionId;
    private String userId;
    private Instant createAt;
    private String createBy;

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getUnionId() {
        return unionId;
    }

    public void setUnionId(String unionId) {
        this.unionId = unionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getCreateAt() {
        return createAt;
    }

    public void setCreateAt(Instant createAt) {
        this.createAt = createAt;
    }

    public String getCreateBy() {
        return createBy;
    }

    public void setCreateBy(String createBy) {
        this.createBy = createBy;
    }
}
