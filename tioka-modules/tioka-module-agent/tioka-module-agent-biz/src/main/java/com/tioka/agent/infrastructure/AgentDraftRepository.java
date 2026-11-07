package com.tioka.agent.infrastructure;

import com.tioka.agent.infrastructure.mapper.AgentDraftMapper;
import com.tioka.agent.infrastructure.persistence.AgentDraftDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Repository;

@Repository
public class AgentDraftRepository {
    private final AgentDraftMapper drafts;

    public AgentDraftRepository(AgentDraftMapper drafts) {
        this.drafts = drafts;
    }

    public AgentDraftDO find(String agentId) {
        return drafts.selectById(agentId);
    }

    public void insert(AgentDraftDO row) {
        drafts.insert(row);
    }

    public void update(AgentDraftDO row) {
        int n = drafts.updateById(row);
        if (n == 0) {
            throw new BizException(ErrorCode.STALE_REVISION, "草稿 revision 冲突: " + row.getAgentId());
        }
    }
}
