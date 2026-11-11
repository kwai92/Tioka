package com.tioka.infra.infrastructure;

import com.tioka.infra.infrastructure.mapper.SysExternalIdentityMapper;
import com.tioka.infra.infrastructure.persistence.SysExternalIdentityDO;

/** sys_external_identity 仓储（FND-09 外部身份映射，NTF 收件人解析消费）。
 *  经 InfraNotifyAutoConfiguration 条件装配（tioka.notify.enabled）。 */
public class SysExternalIdentityRepository {

    private final SysExternalIdentityMapper mapper;

    public SysExternalIdentityRepository(SysExternalIdentityMapper mapper) {
        this.mapper = mapper;
    }

    /** 按 Tioka 用户 ID + 来源查外部 ID；无映射返回 null。 */
    public String findExternalIdByUser(String userId, String source) {
        SysExternalIdentityDO row = mapper.findByUserAndSource(userId, source);
        return row == null ? null : row.getExternalId();
    }

    /** 兜底：recipient 本身即外部员工号 / open_id 时反查确认；无映射返回 null。 */
    public String findExternalId(String externalId, String source) {
        SysExternalIdentityDO row = mapper.findByExternalIdAndSource(externalId, source);
        return row == null ? null : row.getExternalId();
    }
}
