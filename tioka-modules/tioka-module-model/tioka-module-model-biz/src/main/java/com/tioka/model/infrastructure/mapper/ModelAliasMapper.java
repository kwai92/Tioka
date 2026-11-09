package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelAliasDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ModelAliasMapper extends BaseMapper<ModelAliasDO> {

    /** 按稳定名称查询生效别名（sys.chat 等系统别名解析入口）。 */
    ModelAliasDO findByName(@Param("name") String name);
}
