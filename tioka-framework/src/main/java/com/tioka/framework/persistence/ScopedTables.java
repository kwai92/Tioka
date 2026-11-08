package com.tioka.framework.persistence;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ScopedTables {
    private final Set<String> exempt;

    public ScopedTables(PersistenceProperties props) {
        this.exempt = props.getWorkspaceScopeExemptTables().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 表是否旁路 ScopeLine（deny-by-default 下的 exempt）。 */
    public boolean isExempt(String tableName) {
        if (tableName == null) {
            return false;
        }
        return exempt.contains(tableName.toLowerCase(Locale.ROOT));
    }

    /**
     * 表是否应加 workspace 过滤（非 exempt）。
     * @deprecated 语义保留给测试；生产请用 {@link #isExempt(String)} 反转判断。
     */
    @Deprecated
    public boolean workspaceScoped(String tableName) {
        return !isExempt(tableName);
    }

    public Set<String> exemptTables() {
        return exempt;
    }
}
