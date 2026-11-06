package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.RuntimeSessionMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeIdemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * 旧会话仓储。RT-02 后幂等改走 {@code runtime_turn.client_msg_id}；parts_json 等列已删除。
 */
@Repository
public class RuntimeSessionRepository {
    private final RuntimeSessionMapper sessions;
    private final TurnRepository turns;

    public RuntimeSessionRepository(RuntimeSessionMapper sessions, TurnRepository turns) {
        this.sessions = sessions;
        this.turns = turns;
    }

    public void upsert(RuntimeSessionDO row) {
        if (sessions.selectById(row.getId()) == null) {
            sessions.insert(row);
        } else {
            sessions.updateById(row);
        }
    }

    public RuntimeSessionDO find(String id) {
        return sessions.selectById(id);
    }

    public String findTurnId(String sessionId, String clientMsgId) {
        return turns.findBySessionAndClientMsgId(sessionId, clientMsgId)
                .map(RuntimeTurnDO::getId)
                .orElse(null);
    }

    /** 兼容旧 {@link com.tioka.runtime.application.RuntimeSessionService} 幂等写入。 */
    public void insertIdem(RuntimeIdemDO row) {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(row.getTurnId());
        t.setSessionId(row.getSessionId());
        t.setSeq(turns.nextSeq(row.getSessionId()));
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(row.getClientMsgId());
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        try {
            turns.insert(t);
        } catch (DuplicateKeyException ignored) {
            // 并发下已有同 client_msg_id
        }
    }
}
