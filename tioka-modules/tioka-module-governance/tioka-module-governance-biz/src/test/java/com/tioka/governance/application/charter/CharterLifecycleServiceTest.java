package com.tioka.governance.application.charter;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.charter.AgentCharterPort;
import com.tioka.governance.api.charter.CharterEventKind;
import com.tioka.governance.api.charter.CharterEventWriter;
import com.tioka.governance.api.charter.CharterTurnInterruptPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRU-03 状态机纯逻辑单测（M3-05 §4）：
 * 禁用/移除 → SUSPEND（CAS 幂等，重复事件不重复写事件）；交接 HANDOVER → 确认 ACTIVE；
 * 权限校验；取消信号按版本集合发出。
 */
class CharterLifecycleServiceTest {

    private static final Principal ADMIN = new Principal("u_admin", "ws_1", PrincipalType.USER,
            Set.of("workspace_admin"), Set.of(Permissions.WORKSPACE_MANAGE), "password");

    /** 可编程端口桩：charterStatus 即 DB 状态，转移按 CAS 语义模拟。 */
    private static final class FakeAgents implements AgentCharterPort {
        final Map<String, String[]> rows = new java.util.LinkedHashMap<>();
        final AtomicInteger suspendAttempts = new AtomicInteger();

        void put(String id, String ws, String owner, String status) {
            rows.put(id, new String[]{ws, owner, status});
        }

        @Override public List<CharterAgentView> listOwnedBy(String userId) {
            return rows.entrySet().stream()
                    .filter(e -> userId.equals(e.getValue()[1]))
                    .map(e -> new CharterAgentView(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                    .toList();
        }

        @Override public List<CharterAgentView> listOwnedByInWorkspace(String ws, String userId) {
            return listOwnedBy(userId).stream()
                    .filter(v -> ws.equals(v.workspaceId())).toList();
        }

        @Override public CharterAgentView getAgent(String agentId) {
            String[] v = rows.get(agentId);
            return v == null ? null : new CharterAgentView(agentId, v[0], v[1], v[2]);
        }

        @Override public String charterStatus(String agentId) {
            String[] v = rows.get(agentId);
            return v == null ? null : v[2];
        }

        @Override public boolean transitionToSuspended(String agentId) {
            suspendAttempts.incrementAndGet();
            String[] v = rows.get(agentId);
            if (v == null || !"ACTIVE".equals(v[2])) {
                return false;
            }
            v[2] = "SUSPENDED";
            return true;
        }

        @Override public boolean transitionToHandover(String agentId, String newOwnerId) {
            String[] v = rows.get(agentId);
            if (v == null || (!"ACTIVE".equals(v[2]) && !"SUSPENDED".equals(v[2]))) {
                return false;
            }
            v[1] = newOwnerId;
            v[2] = "HANDOVER";
            return true;
        }

        @Override public boolean transitionToActive(String agentId) {
            String[] v = rows.get(agentId);
            if (v == null || !"HANDOVER".equals(v[2])) {
                return false;
            }
            v[2] = "ACTIVE";
            return true;
        }

        @Override public List<String> readableVersionIds(String agentId) {
            return rows.containsKey(agentId) ? List.of("agv_" + agentId) : List.of();
        }
    }

    private static final class RecordingEvents implements CharterEventWriter {
        final List<AppendCommand> appended = new java.util.ArrayList<>();

        @Override public void append(AppendCommand command) {
            appended.add(command);
        }
    }

    private static final class RecordingInterrupts implements CharterTurnInterruptPort {
        final Map<List<String>, String> calls = new java.util.LinkedHashMap<>();

        @Override public int interruptActiveTurns(List<String> agentVersionIds, String reason) {
            calls.put(agentVersionIds, reason);
            return agentVersionIds.size();
        }
    }

    private static PrincipalHolder holder(Principal p) {
        return new PrincipalHolder() {
            @Override public Principal current() { return p; }
            @Override public java.util.Optional<Principal> find() { return java.util.Optional.of(p); }
            @Override public <T> T runAs(Principal principal, Supplier<T> body) { return body.get(); }
        };
    }

    private static CharterLifecycleService service(FakeAgents agents, RecordingEvents events,
                                                   RecordingInterrupts interrupts, PrincipalHolder holder) {
        return new CharterLifecycleService(agents, interrupts, events, holder);
    }

    @Test
    void ownerDisableSuspendsOwnedAgentsAcrossWorkspaces() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_owner", "ACTIVE");
        agents.put("ag_2", "ws_2", "u_owner", "ACTIVE");
        agents.put("ag_3", "ws_1", "u_other", "ACTIVE");
        RecordingEvents events = new RecordingEvents();
        RecordingInterrupts interrupts = new RecordingInterrupts();
        CharterLifecycleService svc = service(agents, events, interrupts, holder(ADMIN));

        svc.suspendByOwnerDisable("u_owner");

        assertEquals("SUSPENDED", agents.charterStatus("ag_1"));
        assertEquals("SUSPENDED", agents.charterStatus("ag_2"));
        assertEquals("ACTIVE", agents.charterStatus("ag_3"), "他人 Agent 不受影响");
        assertEquals(2, events.appended.size());
        assertTrue(events.appended.stream().allMatch(c -> c.kind() == CharterEventKind.SUSPEND));
        assertTrue(events.appended.stream().allMatch(c -> c.reason().startsWith("owner_disabled:u_owner")));
        assertTrue(interrupts.calls.containsKey(List.of("agv_ag_1")), "SUSPEND 同时取消运行中 Turn");
    }

    @Test
    void repeatedEventIsIdempotentNoDuplicateSuspend() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_owner", "ACTIVE");
        RecordingEvents events = new RecordingEvents();
        CharterLifecycleService svc = service(agents, events, new RecordingInterrupts(), holder(ADMIN));

        svc.suspendByOwnerDisable("u_owner");
        svc.suspendByOwnerDisable("u_owner"); // MQ 重投 / 进程内双路
        svc.suspendByMemberRemove("ws_1", "u_owner"); // 另一来源重复

        assertEquals(1, events.appended.size(), "重复事件只处理一次（状态转移幂等）");
        assertEquals(3, agents.suspendAttempts.get(), "三次 CAS 尝试，后两次命中 0 行");
    }

    @Test
    void memberRemoveScopedToWorkspace() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_owner", "ACTIVE");
        agents.put("ag_2", "ws_2", "u_owner", "ACTIVE");
        RecordingEvents events = new RecordingEvents();
        CharterLifecycleService svc = service(agents, events, new RecordingInterrupts(), holder(ADMIN));

        svc.suspendByMemberRemove("ws_1", "u_owner");

        assertEquals("SUSPENDED", agents.charterStatus("ag_1"));
        assertEquals("ACTIVE", agents.charterStatus("ag_2"), "其他空间的同名负责人 Agent 不受影响");
        assertEquals("member_removed:ws_1:u_owner", events.appended.get(0).reason());
    }

    @Test
    void handoverThenAcceptRestoresActive() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_owner", "SUSPENDED");
        RecordingEvents events = new RecordingEvents();
        CharterLifecycleService svc = service(agents, events, new RecordingInterrupts(), holder(ADMIN));

        svc.assignOwner("ws_1", "ag_1", "u_new");
        assertEquals("HANDOVER", agents.charterStatus("ag_1"));

        // ADR-032：确认动作必须由交接后的新负责人发起
        Principal newOwner = new Principal("u_new", "ws_1", PrincipalType.USER,
                Set.of("member"), Set.of(), "password");
        service(agents, events, new RecordingInterrupts(), holder(newOwner))
                .acceptCharter("ws_1", "ag_1");
        assertEquals("ACTIVE", agents.charterStatus("ag_1"));

        assertEquals(2, events.appended.size());
        assertEquals(CharterEventKind.HANDOVER, events.appended.get(0).kind());
        assertEquals("u_owner", events.appended.get(0).fromOwner());
        assertEquals("u_new", events.appended.get(0).toOwner());
        assertEquals(CharterEventKind.ACCEPT, events.appended.get(1).kind());
    }

    @Test
    void assignOwnerRequiresWorkspaceManage() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_owner", "ACTIVE");
        RecordingEvents events = new RecordingEvents();
        Principal member = new Principal("u_member", "ws_1", PrincipalType.USER,
                Set.of("member"), Set.of(), "password");
        CharterLifecycleService svc = service(agents, events, new RecordingInterrupts(), holder(member));

        BizException ex = assertThrows(BizException.class, () -> svc.assignOwner("ws_1", "ag_1", "u_new"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertTrue(events.appended.isEmpty());
    }

    @Test
    void acceptCharterRejectsNonOwner() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_new", "HANDOVER");
        RecordingEvents events = new RecordingEvents();
        // 空间管理员（有 workspace:manage）也不是新负责人，同样不得代确认
        CharterLifecycleService svc = service(agents, events, new RecordingInterrupts(), holder(ADMIN));

        BizException ex = assertThrows(BizException.class, () -> svc.acceptCharter("ws_1", "ag_1"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        assertEquals("HANDOVER", agents.charterStatus("ag_1"), "越权确认不得改变状态");
        assertTrue(events.appended.isEmpty(), "越权确认不得写 ACCEPT 事件");
    }

    @Test
    void assignOwnerAndAcceptRejectCrossWorkspace() {
        FakeAgents agents = new FakeAgents();
        agents.put("ag_1", "ws_1", "u_new", "HANDOVER");
        RecordingEvents events = new RecordingEvents();

        BizException assign = assertThrows(BizException.class,
                () -> service(agents, events, new RecordingInterrupts(), holder(ADMIN))
                        .assignOwner("ws_2", "ag_1", "u_other"));
        assertEquals(ErrorCode.FORBIDDEN, assign.getErrorCode());

        Principal newOwner = new Principal("u_new", "ws_2", PrincipalType.USER,
                Set.of("member"), Set.of(), "password");
        BizException accept = assertThrows(BizException.class,
                () -> service(agents, events, new RecordingInterrupts(), holder(newOwner))
                        .acceptCharter("ws_2", "ag_1"));
        assertEquals(ErrorCode.FORBIDDEN, accept.getErrorCode());
        assertEquals("HANDOVER", agents.charterStatus("ag_1"));
        assertTrue(events.appended.isEmpty(), "跨空间操作不得把事件写进别的空间");
    }
}
