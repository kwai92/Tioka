package com.tioka.runtime.kernel.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.runtime.infrastructure.mapper.RuntimeItemMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class ItemRepository {
    /** listAfterSeq 的 limit 上限（48-CS：禁止魔法值）。 */
    public static final int MAX_LIST_AFTER_SEQ = 500;

    private final RuntimeItemMapper mapper;

    public ItemRepository(RuntimeItemMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验租约 → 分配 seq → 插入（RUNNING Turn）。 */
    @Transactional
    public RuntimeItemDO append(ItemWrite write) {
        return write(requireLease(write));
    }

    /** startTurn 时写用户输入：Turn 仍为 QUEUED，不校验租约。 */
    @Transactional
    public RuntimeItemDO appendWithoutLease(ItemWrite write) {
        ItemWrite w = write.leaseVersion() == null ? write
                : new ItemWrite(write.threadId(), write.turnId(), null, write.seq(),
                write.step(), write.type(), write.role(), write.contentJson(), write.tokenCount(),
                write.dataLabel());
        return write(w);
    }

    @Transactional
    public long reserveSeq(String threadId, String turnId, long leaseVersion) {
        if (mapper.assertLease(turnId, leaseVersion) == null) {
            throw new BizException(ErrorCode.LEASE_LOST);
        }
        return mapper.nextItemSeq(threadId);
    }

    @Transactional
    public void insertReserved(ItemWrite write) {
        write(requireLease(write));
    }

    public List<RuntimeItemDO> listByThread(String threadId) {
        return mapper.listByThread(threadId);
    }

    public List<RuntimeItemDO> listAfterSeq(String threadId, long afterSeq, int limit) {
        int lim = Math.min(Math.max(limit, 1), MAX_LIST_AFTER_SEQ);
        return mapper.listAfterSeq(threadId, afterSeq, lim);
    }

    public List<RuntimeItemDO> listByTurn(String turnId) {
        return mapper.listByTurn(turnId);
    }

    private ItemWrite requireLease(ItemWrite write) {
        if (write.leaseVersion() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "leaseVersion 不能为空");
        }
        if (mapper.assertLease(write.turnId(), write.leaseVersion()) == null) {
            throw new BizException(ErrorCode.LEASE_LOST);
        }
        return write;
    }

    private RuntimeItemDO write(ItemWrite w) {
        long seq = w.seq() != null ? w.seq() : mapper.nextItemSeq(w.threadId());
        RuntimeItemDO item = newItem(w, seq);
        mapper.insert(item);
        return item;
    }

    private static RuntimeItemDO newItem(ItemWrite w, long seq) {
        RuntimeItemDO item = new RuntimeItemDO();
        item.setThreadId(w.threadId());
        item.setTurnId(w.turnId());
        item.setSeq(seq);
        item.setId(Ids.newId("itm"));
        item.setStep(w.step());
        item.setType(w.type());
        item.setRole(w.role());
        item.setContentJson(w.contentJson());
        item.setTokenCount(w.tokenCount());
        item.setDataLabel(w.dataLabel());
        item.setDiscarded(false);
        return item;
    }
}
