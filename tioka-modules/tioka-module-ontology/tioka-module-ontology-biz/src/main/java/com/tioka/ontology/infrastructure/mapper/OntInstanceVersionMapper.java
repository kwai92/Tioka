package com.tioka.ontology.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code ont_instance_version}。复合主键 (instance_key, version)；无 workspace_id 列，旁路 ScopeLine。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OntInstanceVersionMapper extends BaseMapper<OntInstanceVersionDO> {

    int insertVersion(@Param("row") OntInstanceVersionDO row);

    OntInstanceVersionDO findByKey(@Param("instanceKey") String instanceKey, @Param("version") int version);

    /** 最新 CONFIRMED 版本（confirmed_at 最大，并列取 version 最大）。 */
    OntInstanceVersionDO findLatestConfirmed(@Param("instanceKey") String instanceKey);

    List<OntInstanceVersionDO> listConfirmedByStore(@Param("storeKey") String storeKey);

    /** 把该实例其余 CONFIRMED 版本标 SUPERSEDED（确认单事务内调用）。 */
    int supersedeOthers(@Param("instanceKey") String instanceKey, @Param("keepVersion") int keepVersion);
}
