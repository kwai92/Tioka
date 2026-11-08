package com.tioka.framework.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 进程内登记表空实现；PK-14a 可替换为持久化实现。 */
public final class InMemoryCredentialReencryptRegistry implements CredentialReencryptRegistry {

    private final List<CredentialColumn> columns = new ArrayList<>();

    @Override
    public synchronized void register(CredentialColumn column) {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(column.table(), "table");
        Objects.requireNonNull(column.column(), "column");
        columns.add(column);
    }

    @Override
    public synchronized List<CredentialColumn> columns() {
        return Collections.unmodifiableList(List.copyOf(columns));
    }
}
