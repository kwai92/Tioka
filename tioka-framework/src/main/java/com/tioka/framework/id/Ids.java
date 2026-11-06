package com.tioka.framework.id;

import com.github.f4b6a3.ulid.UlidCreator;

/**
 * 统一 ID 生成（ENG-05 / ADR-029 第 9 条）。例：{@code Ids.newId("turn")} → {@code turn_01JC…}。
 */
public final class Ids {
    private Ids() {}

    public static String newId(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix required");
        }
        return prefix + "_" + UlidCreator.getMonotonicUlid().toString();
    }
}
