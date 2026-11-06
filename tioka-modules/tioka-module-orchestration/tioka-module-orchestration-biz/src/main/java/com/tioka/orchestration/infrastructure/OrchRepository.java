package com.tioka.orchestration.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.orchestration.infrastructure.mapper.OrchAttentionMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchIdemMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchWhyMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchAttentionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchIdemDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWhyDO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class OrchRepository {
    private final OrchRunMapper runs;
    private final OrchIdemMapper idem;
    private final OrchAttentionMapper attentions;
    private final OrchWhyMapper whys;
    private final OrchRuleMapper rules;

    public OrchRepository(OrchRunMapper runs, OrchIdemMapper idem, OrchAttentionMapper attentions,
                          OrchWhyMapper whys, OrchRuleMapper rules) {
        this.runs = runs;
        this.idem = idem;
        this.attentions = attentions;
        this.whys = whys;
        this.rules = rules;
    }

    public String findRunIdByIdem(String key) {
        OrchIdemDO row = idem.selectById(key);
        return row == null ? null : row.getRunId();
    }

    public void insertIdem(OrchIdemDO row) { idem.insert(row); }
    public void insertRun(OrchRunDO row) { runs.insert(row); }
    public void updateRun(OrchRunDO row) { runs.updateById(row); }
    public OrchRunDO findRun(String id) { return runs.selectById(id); }

    /** ORC-01：同事务内 SKIP LOCKED 锁定并标记 LEASED；无可用时返回 null。 */
    @Transactional
    public String claimOne(String workerId, long leaseMillis) {
        long now = System.currentTimeMillis();
        String id = runs.lockNextClaimable(now);
        if (id == null) {
            return null;
        }
        int n = runs.markLeased(id, workerId, now + leaseMillis);
        return n == 1 ? id : null;
    }

    public void insertAttention(OrchAttentionDO row) { attentions.insert(row); }
    public void updateAttention(OrchAttentionDO row) { attentions.updateById(row); }
    public OrchAttentionDO findAttention(String id) { return attentions.selectById(id); }
    public List<OrchAttentionDO> listOpenAttentions() {
        return attentions.selectList(Wrappers.<OrchAttentionDO>lambdaQuery().eq(OrchAttentionDO::getStatus, "OPEN"));
    }

    public int nextWhyOrd(String runId) {
        List<OrchWhyDO> list = whys.selectList(Wrappers.<OrchWhyDO>lambdaQuery().eq(OrchWhyDO::getRunId, runId));
        int max = 0;
        for (OrchWhyDO w : list) {
            if (w.getOrd() != null && w.getOrd() > max) max = w.getOrd();
        }
        return max + 1;
    }

    public void insertWhy(OrchWhyDO row) { whys.insert(row); }

    public List<OrchWhyDO> listWhy(String runId) {
        return whys.selectList(Wrappers.<OrchWhyDO>lambdaQuery()
                .eq(OrchWhyDO::getRunId, runId)
                .orderByAsc(OrchWhyDO::getOrd));
    }

    public void insertRule(OrchRuleDO row) { rules.insert(row); }
    public void updateRule(OrchRuleDO row) { rules.updateById(row); }
    public OrchRuleDO findRule(String id) { return rules.selectById(id); }
    public List<OrchRuleDO> listRules() {
        return rules.selectList(Wrappers.<OrchRuleDO>lambdaQuery().orderByDesc(OrchRuleDO::getCreateAt));
    }

    /** 已发布且 event_type 匹配的最高 version 规则。 */
    public OrchRuleDO findLatestPublishedRule(String eventType) {
        List<OrchRuleDO> list = rules.selectList(Wrappers.<OrchRuleDO>lambdaQuery()
                .eq(OrchRuleDO::getEventType, eventType)
                .eq(OrchRuleDO::getPublished, true)
                .orderByDesc(OrchRuleDO::getVersion)
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    public int nextRuleVersion(String eventType) {
        List<OrchRuleDO> list = rules.selectList(Wrappers.<OrchRuleDO>lambdaQuery()
                .eq(OrchRuleDO::getEventType, eventType));
        int max = 0;
        for (OrchRuleDO r : list) {
            if (r.getVersion() != null && r.getVersion() > max) max = r.getVersion();
        }
        return max + 1;
    }

    public long countRuns() {
        return runs.selectCount(Wrappers.emptyWrapper());
    }
}
