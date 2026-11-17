package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelStructuredCapabilityDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code model_structured_capability}。主键 model_ref；无 workspace_id 列，旁路 ScopeLine。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ModelStructuredCapabilityMapper extends BaseMapper<ModelStructuredCapabilityDO> {

    /** upsert：ON CONFLICT (model_ref) DO UPDATE（能力登记幂等覆盖）。 */
    int upsert(@Param("row") ModelStructuredCapabilityDO row);
}
