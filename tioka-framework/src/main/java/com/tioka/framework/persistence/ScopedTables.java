package com.tioka.framework.persistence;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ScopedTables {
    private final Set<String> workspaceScoped;

    public ScopedTables(PersistenceProperties props) {
        this.workspaceScoped = props.getWorkspaceScopedTables().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean workspaceScoped(String tableName) {
        if (tableName == null) {
            return false;
        }
        return workspaceScoped.contains(tableName.toLowerCase(Locale.ROOT));
    }

    public Set<String> workspaceScoped() {
        return workspaceScoped;
    }
}
