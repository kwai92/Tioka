package com.tioka.infra.messaging;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.api.DeadLetterOpsController;
import com.tioka.infra.messaging.infrastructure.SysDeadLetterRepository;
import com.tioka.infra.messaging.infrastructure.persistence.SysDeadLetterDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ORC-07 / FE-17 死信运维 REST 出口 mock 测试（离线，MockMvc standalone）：
 * 路由、分页参数、payload 截断、replay/discard 请求体与权限外的服务编排。
 * 权限（orch:operate）由运行时拦截器与 ArchUnit controllersMustDeclarePermission 守护。
 */
class DeadLetterOpsControllerTest {

    private DeadLetterOpsService ops;
    private PrincipalHolder principals;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ops = mock(DeadLetterOpsService.class);
        principals = mock(PrincipalHolder.class);
        when(principals.current()).thenReturn(new Principal("42", "ws-1", PrincipalType.USER,
                Set.of(), Set.of(), "password"));
        mvc = MockMvcBuilders.standaloneSetup(new DeadLetterOpsController(ops, principals)).build();
    }

    private static SysDeadLetterDO row() {
        SysDeadLetterDO row = new SysDeadLetterDO();
        row.setId("dl1");
        row.setConsumer("orch-dispatcher");
        row.setTopic("tioka-runtime");
        row.setTag("demo.failed");
        row.setEventId("evt-1");
        row.setPayloadJson("x".repeat(3000));
        row.setError("boom");
        row.setAttempts(3);
        row.setStatus(SysDeadLetterRepository.STATUS_OPEN);
        row.setCreateAt(Instant.parse("2026-11-10T00:00:00Z"));
        return row;
    }

    @Test
    void listReturnsPagedViewWithTruncatedPayload() throws Exception {
        when(ops.list(isNull(), eq("OPEN"), eq(2), eq(5)))
                .thenReturn(new DeadLetterOpsService.Page<>(java.util.List.of(row()), 1L, 2, 5));

        mvc.perform(get("/api/ops/dead-letters").param("status", "OPEN")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value("dl1"))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].payload").value("x".repeat(2000)));
    }

    @Test
    void replayDelegatesToService() throws Exception {
        SysDeadLetterDO replayed = row();
        replayed.setStatus(SysDeadLetterRepository.STATUS_REPLAYED);
        when(ops.replay("dl1", "42")).thenReturn(replayed);

        mvc.perform(post("/api/ops/dead-letters/dl1/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REPLAYED"));
        verify(ops).replay("dl1", "42");
    }

    @Test
    void discardPassesReasonAndOperator() throws Exception {
        SysDeadLetterDO discarded = row();
        discarded.setStatus(SysDeadLetterRepository.STATUS_DISCARDED);
        when(ops.replay(any(), any())).thenThrow(new AssertionError("不应调 replay"));
        when(ops.discard(eq("dl1"), eq("42"), eq("fixed upstream"))).thenReturn(discarded);

        mvc.perform(post("/api/ops/dead-letters/dl1/discard")
                        .contentType("application/json")
                        .content("{\"reason\":\"fixed upstream\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCARDED"));
        verify(ops).discard("dl1", "42", "fixed upstream");
    }

    @Test
    void discardWithoutBodyUsesNullReason() throws Exception {
        SysDeadLetterDO discarded = row();
        discarded.setStatus(SysDeadLetterRepository.STATUS_DISCARDED);
        when(ops.discard(eq("dl1"), eq("42"), isNull())).thenReturn(discarded);

        mvc.perform(post("/api/ops/dead-letters/dl1/discard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISCARDED"));
        verify(ops).discard("dl1", "42", null);
    }
}
