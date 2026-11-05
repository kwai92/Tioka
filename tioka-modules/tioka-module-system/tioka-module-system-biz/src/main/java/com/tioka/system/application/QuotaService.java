package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.infrastructure.SysQuotaRepository;
import com.tioka.system.infrastructure.persistence.SysQuotaDO;
import org.springframework.stereotype.Service;

@Service
public class QuotaService {
    private final SysQuotaRepository quotas;

    public QuotaService(SysQuotaRepository quotas) {
        this.quotas = quotas;
    }

    public record Limits(int tokenLimit, int concurrentLimit) {}

    public Limits setLimits(String workspaceId, int tokenLimit, int concurrentLimit) {
        Limits l = new Limits(Math.max(1, tokenLimit), Math.max(1, concurrentLimit));
        SysQuotaDO row = quotas.findByWorkspaceId(workspaceId);
        if (row == null) {
            row = new SysQuotaDO();
            row.setWorkspaceId(workspaceId);
            row.setTokenUsed(0);
            row.setConcurrentUsed(0);
        }
        row.setTokenLimit(l.tokenLimit());
        row.setConcurrentLimit(l.concurrentLimit());
        if (row.getTokenUsed() == null) row.setTokenUsed(0);
        if (row.getConcurrentUsed() == null) row.setConcurrentUsed(0);
        quotas.save(row);
        return l;
    }

    public Limits limitsOf(String workspaceId) {
        SysQuotaDO row = quotas.findByWorkspaceId(workspaceId);
        return row == null ? new Limits(1_000_000, 32)
                : new Limits(row.getTokenLimit(), row.getConcurrentLimit());
    }

    public void acquire(String workspaceId) {
        ensureRow(workspaceId);
        Limits l = limitsOf(workspaceId);
        if (quotas.tryAcquire(workspaceId, l.concurrentLimit()) == 0) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, "并发超限");
        }
    }

    public void release(String workspaceId) {
        ensureRow(workspaceId);
        quotas.release(workspaceId);
    }

    public void consumeTokens(String workspaceId, int tokens) {
        ensureRow(workspaceId);
        Limits l = limitsOf(workspaceId);
        if (quotas.consumeTokens(workspaceId, tokens, l.tokenLimit()) == 0) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, "Token 配额超限");
        }
    }

    private void ensureRow(String workspaceId) {
        if (quotas.findByWorkspaceId(workspaceId) == null) {
            setLimits(workspaceId, 1_000_000, 32);
        }
    }
}
