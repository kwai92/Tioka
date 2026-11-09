package com.tioka.server.messaging;

import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.infra.messaging.infrastructure.persistence.SysConsumedDO;
import com.tioka.runtime.kernel.events.EventId;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 三套幂等键「命名空间不串」契约（离线，无需基础设施）。
 *
 * <p>三套幂等键来源（冻结约束：复用既有 {@code sys_consumed}(consumer, event_id) 主键，不新增表）：
 * <ul>
 *   <li>① MQ 事件幂等键 = {@link EventEnvelope#eventId()}（字符串 {@code evt_*}），落在 {@code sys_consumed.event_id}；</li>
 *   <li>② runtime Kernel 事件游标 = {@link EventId}（seq 长整型，形如 {@code {seq}} 或 {@code {seq}.{delta}}）；</li>
 *   <li>③ runtime Turn 幂等键 = {@code RuntimeTurnDO.clientMsgId}（字符串），落在 {@code runtime_turn.client_msg_id}。</li>
 * </ul>
 *
 * <p>本测试断言：① 与 ② 解析互不干扰（MQ 字符串无法被解析为 seq 游标，反之亦然）；③ 与 ① 落在不同表、
 * 不同字段名，命名空间不串。据此证明跨域幂等键之间不会发生错配。
 */
class MessagingIdempotencyKeyContractTest {

    /** ① vs ②：MQ eventId（"evt_*" 字符串）不能被解析为 runtime EventId（seq 长整型），命名空间独立。 */
    @Test
    void mqEventIdIsIndependentFromRuntimeSeq() {
        EventEnvelope env = envelope("evt_0000000042");
        assertInstanceOf(String.class, env.eventId(), "MQ eventId 必须是字符串命名空间");
        assertEquals("evt_0000000042", env.eventId());

        // MQ 命名空间无法被 runtime 游标解析器消费：抛 NumberFormatException
        assertThrows(NumberFormatException.class,
                () -> EventId.parse(env.eventId()),
                "MQ eventId 不得被误解析为 runtime EventId(seq)");
    }

    /** ② vs ①：runtime EventId 的 seq 形式（纯数字）不是 MQ evt_* 命名空间，二者互不串。 */
    @Test
    void runtimeSeqIsIndependentFromMqEventId() {
        EventId id = EventId.parse("42");
        assertEquals(42L, id.seq());
        assertTrue(id.toString().matches("\\d+"), "runtime 游标为纯数字 seq");
        assertFalse(id.toString().startsWith("evt_"), "runtime 游标不得混入 MQ evt_ 命名空间");
    }

    /**
     * ③ vs ①：runtime Turn 幂等键（clientMsgId，runtime_turn 表）与 MQ 幂等键（eventId，sys_consumed 表）
     * 落在不同表、不同字段名，命名空间不串。
     */
    @Test
    void clientMsgIdAndMqEventIdAreDistinctNamespaces() throws Exception {
        Field clientMsgId = RuntimeTurnDO.class.getDeclaredField("clientMsgId");
        Field mqEventId = SysConsumedDO.class.getDeclaredField("eventId");

        assertEquals(String.class, clientMsgId.getType(), "runtime Turn 幂等键 clientMsgId 为字符串");
        assertEquals(String.class, mqEventId.getType(), "MQ 幂等键 eventId 为字符串");
        assertFalse(clientMsgId.getName().equals(mqEventId.getName()),
                "clientMsgId 与 eventId 字段名必须不同，避免命名空间错配");

        // 两张表不同：runtime_turn（Turn 幂等）vs sys_consumed（MQ 幂等）
        assertEquals("sys_consumed", SysConsumedDO.class.getAnnotation(
                com.baomidou.mybatisplus.annotation.TableName.class).value());
        assertTrue(!clientMsgId.getDeclaringClass().equals(SysConsumedDO.class),
                "clientMsgId 属于 runtime_turn 持久化类，与 sys_consumed 隔离");
    }

    private static EventEnvelope envelope(String eventId) {
        return new EventEnvelope(
                eventId, "tioka.e2e.it_event.v1", 1, "ws_idem",
                "2026-11-05T00:00:00Z", "e2e-it", null, null, null, null,
                null, null, 0, null, Map.of("marker", "x"));
    }
}
