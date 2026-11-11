package com.tioka.ontology.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.ontology.infrastructure.persistence.OntStateDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code ont_state}。主键 store_key；无 workspace_id 列，旁路 ScopeLine。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OntStateMapper extends BaseMapper<OntStateDO> {

    /** upsert：ON CONFLICT (store_key) DO UPDATE（折叠幂等覆盖）。 */
    int upsert(@Param("row") OntStateDO row);

    OntStateDO findByStoreKey(@Param("storeKey") String storeKey);

    int markWrittenBack(@Param("storeKey") String storeKey);
}
