package com.tioka.runtime.kernel.model;

import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;

/**
 * 模型调用线程内作用域：供 FailoverEvent 同步监听器写 status item / discard。
 */
public final class TurnCallScope {
    private static final ThreadLocal<Holder> TL = new ThreadLocal<>();

    private TurnCallScope() {}

    public static void open(LoopContext ctx, LoopPorts.ItemSink sink, int step) {
        TL.set(new Holder(ctx, sink, step));
    }

    public static void close() {
        TL.remove();
    }

    public static Holder current() {
        return TL.get();
    }

    public record Holder(LoopContext ctx, LoopPorts.ItemSink sink, int step) {}
}
