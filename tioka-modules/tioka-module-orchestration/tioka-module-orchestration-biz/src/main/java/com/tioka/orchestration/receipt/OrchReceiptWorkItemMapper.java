package com.tioka.orchestration.receipt;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
/** 与 infrastructure.OrchWorkItemMapper 同表；Bean 名错开避免冲突。 */
public interface OrchReceiptWorkItemMapper extends BaseMapper<OrchWorkItemDO> {
}
