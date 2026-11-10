package com.tioka.ontology.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code ont_draft}。无 workspace_id 列，旁路 ScopeLine。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OntDraftMapper extends BaseMapper<OntDraftDO> {

    /** 乐观锁更新（M3-03 §7）：revision 匹配才更新并 +1；影响 0 行表示并发冲突。 */
    int updateWithRevision(@Param("row") OntDraftDO row, @Param("expectedRevision") int expectedRevision);
}
