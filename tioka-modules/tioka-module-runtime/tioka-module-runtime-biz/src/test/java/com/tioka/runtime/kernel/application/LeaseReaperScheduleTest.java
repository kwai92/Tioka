package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code LeaseReaper} 的定时回收开关（{@code tioka.runtime.lease-reaper-enabled}）。
 *
 * <p>这个开关的存在理由是：普通 {@code @SpringBootTest} IT 自己 {@code plantRunning} 后显式调
 * {@code reap()} 再断言终态，而后台调度器每 10 s 也在跑，两者会争抢同一行——调度器抢先把行置
 * {@code INTERRUPTED} 后，测试线程的 {@code markInterrupted} 返回 0 而跳过，测试遂读到尚未推进到
 * {@code QUEUED} 的中间态（{@code RecoveryIT:95} 的偶发失败即此形态）。
 *
 * <p>开关只关**定时触发**，绝不能连 {@code reap()} 能力一起关掉——否则上述 IT 就无从驱动恢复语义，
 * 那等于把一个偶发失败换成了一个恒定失败。第三条用例专门守这条边界。
 */
class LeaseReaperScheduleTest {

    @Test
    void 定时入口在开关关闭时不触发回收() {
        Fixture f = new Fixture();
        f.props.setLeaseReaperEnabled(false);

        f.reaper.reapOnSchedule();

        // 连过期租约都不去查，说明守卫在方法入口就返回了，而非查完再判断
        verify(f.turns, never()).findExpired(any(), anyInt());
    }

    @Test
    void 定时入口在开关默认开启时触发回收() {
        Fixture f = new Fixture();
        when(f.turns.findExpired(any(), anyInt())).thenReturn(List.of());

        f.reaper.reapOnSchedule();

        // 默认值必须是 true：生产与 GateCluster 系闸门都依赖定时回收，默认关闭会静默改变行为
        assertEquals(true, f.props.isLeaseReaperEnabled(), "leaseReaperEnabled 默认应为 true");
        verify(f.turns).findExpired(any(), anyInt());
    }

    @Test
    void 显式调用reap不受开关影响() {
        Fixture f = new Fixture();
        f.props.setLeaseReaperEnabled(false);
        when(f.turns.findExpired(any(), anyInt())).thenReturn(List.of());

        f.reaper.reap();

        verify(f.turns).findExpired(any(), anyInt());
    }

    /** 被测对象与替身。{@code KernelProperties} 用真实实例，因为被测的就是它的开关。 */
    private static final class Fixture {

        final TurnRepository turns = mock(TurnRepository.class);
        final KernelProperties props = new KernelProperties();
        final LeaseReaper reaper = new LeaseReaper(turns,
                mock(ItemRepository.class),
                mock(TurnDispatchQueue.class),
                mock(EventStream.class),
                mock(RuntimeEventFactory.class),
                props,
                new ObjectMapper(),
                mock(PrincipalHolder.class));
    }
}
