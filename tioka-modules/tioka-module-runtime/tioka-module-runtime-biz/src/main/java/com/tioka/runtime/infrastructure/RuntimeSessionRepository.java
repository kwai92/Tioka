package com.tioka.runtime.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.runtime.infrastructure.mapper.RuntimeIdemMapper;
import com.tioka.runtime.infrastructure.mapper.RuntimeSessionMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeIdemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import org.springframework.stereotype.Repository;

@Repository
public class RuntimeSessionRepository {
    private final RuntimeSessionMapper sessions;
    private final RuntimeIdemMapper idem;

    public RuntimeSessionRepository(RuntimeSessionMapper sessions, RuntimeIdemMapper idem) {
        this.sessions = sessions;
        this.idem = idem;
    }

    public void upsert(RuntimeSessionDO row) {
        if (sessions.selectById(row.getId()) == null) sessions.insert(row);
        else sessions.updateById(row);
    }

    public RuntimeSessionDO find(String id) { return sessions.selectById(id); }

    public String findTurnId(String sessionId, String clientMsgId) {
        RuntimeIdemDO row = idem.selectOne(Wrappers.<RuntimeIdemDO>lambdaQuery()
                .eq(RuntimeIdemDO::getSessionId, sessionId)
                .eq(RuntimeIdemDO::getClientMsgId, clientMsgId));
        return row == null ? null : row.getTurnId();
    }

    public void insertIdem(RuntimeIdemDO row) { idem.insert(row); }
}
