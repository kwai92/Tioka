package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.PromptTemplateDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** AST-07（ADR-041 §4）：Prompt 模板身份表 Mapper。 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateDO> {

    /** 按稳定名称查询（sys.* 内置模板解析入口）。 */
    PromptTemplateDO findByName(@Param("name") String name);
}
