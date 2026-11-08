package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchWorkItemDO;
import org.apache.ibatis.annotations.Mapper;

/** 与 infrastructure.OrchWorkItemMapper 同表；Bean 名错开避免冲突。 */
@Mapper
public interface OrchReceiptWorkItemMapper extends BaseMapper<OrchWorkItemDO> {
}
