package com.tioka.orchestration.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleVersionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleVersionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * 自动暂停（ORC-07）：显式 set 置暂停标记与失败计数（V131 列）。
     * 用 UpdateWrapper 而非 updateById，避免非 null 更新策略丢字段。
     */
    public void markPaused(String id, LocalDateTime pausedAt, String pausedReason, int consecutiveFailures) {
        rules.update(null, Wrappers.<OrchRuleDO>lambdaUpdate()
                .eq(OrchRuleDO::getId, id)
                .set(OrchRuleDO::getPausedAt, pausedAt)
                .set(OrchRuleDO::getPausedReason, pausedReason)
                .set(OrchRuleDO::getConsecutiveFailures, consecutiveFailures));
    }

    /** 显式恢复：清暂停标记、原因并清零连续失败计数。 */
    public void clearPause(String id) {
        rules.update(null, Wrappers.<OrchRuleDO>lambdaUpdate()
                .eq(OrchRuleDO::getId, id)
                .set(OrchRuleDO::getPausedAt, null)
                .set(OrchRuleDO::getPausedReason, null)
                .set(OrchRuleDO::getConsecutiveFailures, 0));
    }

    /** 未达阈值时仅累加连续失败计数。 */
    public void setConsecutiveFailures(String id, int count) {
        rules.update(null, Wrappers.<OrchRuleDO>lambdaUpdate()
                .eq(OrchRuleDO::getId, id)
                .set(OrchRuleDO::getConsecutiveFailures, count));
    }
}
