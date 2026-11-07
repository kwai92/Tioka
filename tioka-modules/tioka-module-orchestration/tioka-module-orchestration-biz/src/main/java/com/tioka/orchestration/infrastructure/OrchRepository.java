package com.tioka.orchestration.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.orchestration.infrastructure.mapper.OrchEventMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRuleMapper;
import com.tioka.orchestration.infrastructure.mapper.OrchRunMapper;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import org.springframework.stereotype.Repository;

import java.util.List;

/** V132 后仅保留规则头 / 事件 / Run 查询；claim/attention/why 已删除。 */
@Repository
public class OrchRepository {
    private final OrchRunMapper runs;
    private final OrchEventMapper events;
    private final OrchRuleMapper rules;

    public OrchRepository(OrchRunMapper runs, OrchEventMapper events, OrchRuleMapper rules) {
        this.runs = runs;
        this.events = events;
        this.rules = rules;
    }

    public void insertEvent(OrchEventDO row) { events.insert(row); }

    public OrchEventDO findEventByIdem(String key) {
        return events.selectOne(Wrappers.<OrchEventDO>lambdaQuery()
                .eq(OrchEventDO::getIdempotencyKey, key)
                .last("LIMIT 1"));
    }

    public OrchRunDO findRun(String id) { return runs.selectById(id); }

    public void updateRun(OrchRunDO row) { runs.updateById(row); }

    public void insertRule(OrchRuleDO row) { rules.insert(row); }

    public void updateRule(OrchRuleDO row) { rules.updateById(row); }

    public OrchRuleDO findRule(String id) { return rules.selectById(id); }

    public List<OrchRuleDO> listRules() {
        return rules.selectList(Wrappers.<OrchRuleDO>lambdaQuery().orderByDesc(OrchRuleDO::getCreateAt));
    }
}
