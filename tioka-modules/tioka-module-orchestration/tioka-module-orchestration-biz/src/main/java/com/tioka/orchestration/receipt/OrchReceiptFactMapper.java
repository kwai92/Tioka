package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchFactDO;
import org.apache.ibatis.annotations.Mapper;

/** 与 infrastructure.OrchFactMapper 同表；Bean 名错开避免冲突。 */
@Mapper
public interface OrchReceiptFactMapper extends BaseMapper<OrchFactDO> {
}
