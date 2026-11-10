package com.tioka.ontology.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code ont_value_index}。复合主键 (instance_key, version, path)；无 workspace_id 列，旁路 ScopeLine。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OntValueIndexMapper extends BaseMapper<OntValueIndexDO> {

    int insertBatch(@Param("rows") List<OntValueIndexDO> rows);

    int deleteByVersion(@Param("instanceKey") String instanceKey, @Param("version") int version);

    List<OntValueIndexDO> listByVersion(@Param("instanceKey") String instanceKey, @Param("version") int version);
}
