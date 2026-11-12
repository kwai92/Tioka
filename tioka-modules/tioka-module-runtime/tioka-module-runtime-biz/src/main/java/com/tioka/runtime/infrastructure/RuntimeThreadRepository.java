package com.tioka.runtime.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tioka.runtime.infrastructure.mapper.RuntimeThreadMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeIdemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Thread 仓储。RT-02 后幂等改走 {@code runtime_turn.client_msg_id}；parts_json 等列已删除。
 */
@Repository
public class RuntimeThreadRepository {
    private final RuntimeThreadMapper threads;
    private final TurnRepository turns;

    public RuntimeThreadRepository(RuntimeThreadMapper threads, TurnRepository turns) {
        this.threads = threads;
        this.turns = turns;
    }

    public void upsert(RuntimeThreadDO row) {
        if (threads.selectById(row.getId()) == null) {
            threads.insert(row);
        } else {
            threads.updateById(row);
        }
    }

    public RuntimeThreadDO find(String id) {
        return threads.selectById(id);
    }

    /** TRU-03：授权书暂停后按 agent_version 反查运行中 thread（仅 id，避免整行拉取）。 */
    public List<RuntimeThreadDO> listByAgentVersionId(String agentVersionId) {
        return threads.selectList(new QueryWrapper<RuntimeThreadDO>()
                .eq("agent_version_id", agentVersionId));
    }

    public String findTurnId(String threadId, String clientMsgId) {
        return turns.findByThreadAndClientMsgId(threadId, clientMsgId)
                .map(RuntimeTurnDO::getId)
                .orElse(null);
    }

    /** 兼容旧幂等写入路径（client_msg_id → runtime_turn）。 */
    public void insertIdem(RuntimeIdemDO row) {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(row.getTurnId());
        t.setThreadId(row.getThreadId());
        t.setSeq(turns.nextSeq(row.getThreadId()));
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
