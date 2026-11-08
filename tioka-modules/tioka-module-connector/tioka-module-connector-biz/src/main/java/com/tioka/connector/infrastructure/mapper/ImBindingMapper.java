package com.tioka.connector.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.connector.infrastructure.persistence.ImBindingDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImBindingMapper extends BaseMapper<ImBindingDO> {

    /**
     * 入站回调无登录态；按主键旁路 ScopeLine 读取（随后仍校验 channel/status）。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, workspace_id, channel, app_id, secret_ref, encrypt_key_ref,
                   verification_token_ref, card_sign_key_ref, default_agent_id,
                   orch_enabled, identity_match, status,
                   create_at, update_at, create_by, update_by, deleted
            FROM im_binding
            WHERE id = #{id} AND deleted = FALSE
            """)
    ImBindingDO selectByIdUnscoped(@Param("id") String id);
}
