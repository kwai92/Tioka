package com.tioka.orchestration.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OrchRuleRepository {
    private final OrchRuleMapper rules;
    private final OrchRuleVersionMapper versions;
    private final OrchRunMapper runs;

    public OrchRuleRepository(OrchRuleMapper rules, OrchRuleVersionMapper versions, OrchRunMapper runs) {
        this.rules = rules;
        this.versions = versions;
        this.runs = runs;
    }

    public void insertRule(OrchRuleDO row) { rules.insert(row); }

    public void updateRule(OrchRuleDO row) { rules.updateById(row); }

    public OrchRuleDO findRule(String id) { return rules.selectById(id); }

    public List<OrchRuleDO> listRules() {
        return rules.selectList(Wrappers.<OrchRuleDO>lambdaQuery().orderByDesc(OrchRuleDO::getCreateAt));
    }

    public void insertVersion(OrchRuleVersionDO row) { versions.insert(row); }

    public void updateVersion(OrchRuleVersionDO row) { versions.updateById(row); }

    public OrchRuleVersionDO findVersion(String id) { return versions.selectById(id); }

    public List<OrchRuleVersionDO> listVersions(String ruleId) {
        return versions.selectList(Wrappers.<OrchRuleVersionDO>lambdaQuery()
                .eq(OrchRuleVersionDO::getRuleId, ruleId)
                .orderByDesc(OrchRuleVersionDO::getVersion));
    }

    public int nextVersionNo(String ruleId) {
        List<OrchRuleVersionDO> list = versions.selectList(Wrappers.<OrchRuleVersionDO>lambdaQuery()
                .eq(OrchRuleVersionDO::getRuleId, ruleId));
        int max = 0;
        for (OrchRuleVersionDO v : list) {
            if (v.getVersion() != null && v.getVersion() > max) {
                max = v.getVersion();
            }
        }
        return max + 1;
    }

    public OrchRunDO findRun(String id) { return runs.selectById(id); }
}
