package com.tioka.runtime.kernel.events;

import java.util.Objects;

/**
 * SSE 事件 ID：{@code {seq}} 或 {@code {seq}.{deltaIndex}}。
 * 比较：先比 seq；同 seq 时有小数的小于无小数的，小数之间按数值比。
 */
public final class EventId implements Comparable<EventId> {
    private final long seq;
    private final Integer delta; // null = 无小数（item.completed）

    private EventId(long seq, Integer delta) {
        this.seq = seq;
        this.delta = delta;
    }

    public static EventId of(long seq) {
        return new EventId(seq, null);
    }

    public static EventId of(long seq, int deltaIndex) {
        return new EventId(seq, deltaIndex);
    }

    public static EventId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new EventId(0, null);
        }
        String s = raw.trim();
        int dot = s.indexOf('.');
        if (dot < 0) {
            return new EventId(Long.parseLong(s), null);
        }
        return new EventId(Long.parseLong(s.substring(0, dot)), Integer.parseInt(s.substring(dot + 1)));
    }

    public long seq() { return seq; }

    public Integer delta() { return delta; }

    public boolean hasDelta() { return delta != null; }

    @Override
    public int compareTo(EventId o) {
        int c = Long.compare(this.seq, o.seq);
        if (c != 0) {
            return c;
        }
        if (this.delta == null && o.delta == null) {
            return 0;
        }
        if (this.delta == null) {
            return 1; // 无小数 > 有小数
        }
        if (o.delta == null) {
            return -1;
        }
        return Integer.compare(this.delta, o.delta);
    }

    @Override
    public String toString() {
        return delta == null ? Long.toString(seq) : seq + "." + delta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventId that)) return false;
        return seq == that.seq && Objects.equals(delta, that.delta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, delta);
    }
}
