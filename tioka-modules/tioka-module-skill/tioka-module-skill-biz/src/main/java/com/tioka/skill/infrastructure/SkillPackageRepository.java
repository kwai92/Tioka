package com.tioka.skill.infrastructure;
import com.tioka.skill.infrastructure.mapper.SkillPackageLockMapper;
import com.tioka.skill.infrastructure.mapper.SkillPackageMapper;
import com.tioka.skill.infrastructure.persistence.SkillPackageDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SkillPackageRepository {
    private final SkillPackageMapper mapper;
    private final SkillPackageLockMapper lockMapper;

    public SkillPackageRepository(SkillPackageMapper mapper, SkillPackageLockMapper lockMapper) {
        this.mapper = mapper;
        this.lockMapper = lockMapper;
    }

    public void insert(SkillPackageDO row) { mapper.insert(row); }
    public SkillPackageDO findById(String id) { return mapper.selectById(id); }
    public void update(SkillPackageDO row) { mapper.updateById(row); }
    public List<SkillPackageDO> listAll() { return mapper.selectList(null); }

    /** AST-02：FOR SHARE 锁定 skill_package（M1 即 skillVersion）。 */
    public SkillPackageDO lockForShare(String id) { return lockMapper.selectForShare(id); }
}
