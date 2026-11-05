package com.tioka.skill.infrastructure;
import com.tioka.skill.infrastructure.mapper.SkillPackageMapper;
import com.tioka.skill.infrastructure.persistence.SkillPackageDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SkillPackageRepository {
    private final SkillPackageMapper mapper;
    public SkillPackageRepository(SkillPackageMapper mapper) { this.mapper = mapper; }
    public void insert(SkillPackageDO row) { mapper.insert(row); }
    public SkillPackageDO findById(String id) { return mapper.selectById(id); }
    public void update(SkillPackageDO row) { mapper.updateById(row); }
    public List<SkillPackageDO> listAll() { return mapper.selectList(null); }
}
