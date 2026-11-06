package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

/**
 * 复用 MyBatis-Plus TenantLine 机制做 workspace_id 行级过滤（禁止 tenant_id）。
 */
public final class ScopeLineHandler implements TenantLineHandler {
    private final String column;
    private final ScopedTables tables;
    private final PrincipalHolder holder;

    public ScopeLineHandler(String column, ScopedTables tables, PrincipalHolder holder) {
        this.column = column;
        this.tables = tables;
        this.holder = holder;
    }

    @Override
    public Expression getTenantId() {
        Principal p = holder.find().orElseThrow(() ->
                new IllegalStateException("无 Principal 的数据访问：" + column));
        if (p.workspaceId() == null || p.workspaceId().isBlank()) {
            throw new IllegalStateException("无 workspaceId 的数据访问：" + column);
        }
        return new StringValue(p.workspaceId());
    }

    @Override
    public String getTenantIdColumn() {
        return column;
    }

    @Override
    public boolean ignoreTable(String tableName) {
        Principal p = holder.find().orElse(null);
        if (p != null && p.type() == PrincipalType.SYSTEM) {
            return true;
        }
        return !tables.workspaceScoped(tableName);
    }
}
