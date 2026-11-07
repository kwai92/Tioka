package com.tioka.runtime.kernel.infrastructure.redis;

import com.tioka.runtime.kernel.domain.CancelledSignal;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 检查点：先看本地标志，再每 2 秒兜底读 Redis cancel 键。
 */
@Component
public class RedisSignalPort implements LoopPorts.SignalPort {
    private final SignalBus bus;
    private final ConcurrentHashMap<String, AtomicLong> lastPollMs = new ConcurrentHashMap<>();

    public RedisSignalPort(SignalBus bus) {
        this.bus = bus;
    }

    @Override
    public void checkpoint(LoopContext ctx) {
        SignalBus.TurnFlags flags = bus.flags(ctx.turnId());
        if (flags != null && flags.cancelRequested.get()) {
            throw new CancelledSignal(bus.cancelReason(ctx.turnId()));
        }
        long now = System.currentTimeMillis();
        AtomicLong last = lastPollMs.computeIfAbsent(ctx.turnId(), k -> new AtomicLong(0));
        if (now - last.get() >= 2000) {
            last.set(now);
            if (bus.isCancelFlag(ctx.turnId())) {
                if (flags != null) {
                    flags.cancelRequested.set(true);
                }
                throw new CancelledSignal(bus.cancelReason(ctx.turnId()));
            }
        }
    }

    @Override
    public List<String> pollSteers(LoopContext ctx) {
        return bus.drainSteers(ctx.turnId());
    }
}
