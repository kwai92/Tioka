package com.tioka.skill.infrastructure.mapper;

import com.tioka.skill.infrastructure.persistence.SkillPackageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface SkillPackageLockMapper {
        SkillPackageDO selectForShare(@Param("id") String id);
}
