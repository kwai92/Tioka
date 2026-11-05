package com.tioka.runtime.kernel.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.infrastructure.mapper.RuntimeItemMapper;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class ItemRepository {
    private final RuntimeItemMapper mapper;

    public ItemRepository(RuntimeItemMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 校验租约 → 分配 seq → 插入（RUNNING Turn）。
     */
    @Transactional
    public RuntimeItemDO append(String sessionId, String turnId, long leaseVersion,
                                int step, String type, String role, String contentJson, Integer tokenCount) {
        if (mapper.assertLease(turnId, leaseVersion) == null) {
            throw new BizException(ErrorCode.LEASE_LOST);
        }
        Long seq = mapper.nextItemSeq(sessionId);
        RuntimeItemDO item = newItem(sessionId, turnId, seq, step, type, role, contentJson, tokenCount);
        mapper.insert(item);
        return item;
    }

    /**
     * startTurn 时写用户输入：Turn 仍为 QUEUED，不校验租约。
     */
    @Transactional
    public RuntimeItemDO appendWithoutLease(String sessionId, String turnId,
                                            int step, String type, String role, String contentJson) {
        Long seq = mapper.nextItemSeq(sessionId);
        RuntimeItemDO item = newItem(sessionId, turnId, seq, step, type, role, contentJson, null);
        mapper.insert(item);
        return item;
    }

    @Transactional
    public long reserveSeq(String sessionId, String turnId, long leaseVersion) {
        if (mapper.assertLease(turnId, leaseVersion) == null) {
            throw new BizException(ErrorCode.LEASE_LOST);
        }
        return mapper.nextItemSeq(sessionId);
    }

    public void insertReserved(long seq, String sessionId, String turnId, int step,
                               String type, String role, String contentJson, Integer tokenCount) {
        RuntimeItemDO item = newItem(sessionId, turnId, seq, step, type, role, contentJson, tokenCount);
        mapper.insert(item);
    }

    public List<RuntimeItemDO> listBySession(String sessionId) {
        return mapper.listBySession(sessionId);
    }

    public List<RuntimeItemDO> listAfterSeq(String sessionId, long afterSeq, int limit) {
        int lim = Math.min(Math.max(limit, 1), 500);
        return mapper.listAfterSeq(sessionId, afterSeq, lim);
    }

    private static RuntimeItemDO newItem(String sessionId, String turnId, long seq, int step,
                                         String type, String role, String contentJson, Integer tokenCount) {
        RuntimeItemDO item = new RuntimeItemDO();
        item.setSessionId(sessionId);
        item.setTurnId(turnId);
        item.setSeq(seq);
        item.setId(Ids.newId("itm"));
        item.setStep(step);
        item.setType(type);
        item.setRole(role);
        item.setContentJson(contentJson);
        item.setTokenCount(tokenCount);
        item.setDiscarded(false);
        return item;
    }
}
