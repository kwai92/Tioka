package com.tioka.infra.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.infra.infrastructure.persistence.SysExternalIdentityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** sys_external_identity 外部身份映射，无 workspace_id；旁路 ScopeLine。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SysExternalIdentityMapper {

    /** FND-09：按 Tioka 用户 ID + 来源查外部身份。 */
    SysExternalIdentityDO findByUserAndSource(@Param("userId") String userId,
                                              @Param("source") String source);

    /** 兜底：recipient 本身即外部员工号 / open_id。 */
    SysExternalIdentityDO findByExternalIdAndSource(@Param("externalId") String externalId,
                                                    @Param("source") String source);
}
