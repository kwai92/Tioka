package com.tioka.governance.application.label;

import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkKind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去向许可离线测试（DAT-02 / M3-02「去向判定」伪码）：
 * 四 verdict、REDACT 脱敏放行、脱敏后仍敏感回落 DENY/APPROVE、缺省规则、ref 级通配回落。
 * 存储与身份用内存实现（离线优先惯例）。
 */
class SinkGuardIT {

    private static final String WS = "ws_1";

    private static PrincipalHolder holder(String workspaceId) {
        Principal p = new Principal("u_1", workspaceId, PrincipalType.USER,
                null, null, "password");
        return new PrincipalHolder() {
            @Override public Principal current() { return p; }
            @Override public Optional<Principal> find() { return Optional.of(p); }
            @Override public <T> T runAs(Principal principal, java.util.function.Supplier<T> body) { return body.get(); }
        };
    }

    private static class InMemoryRepo implements LabelSinkRepository {
        final Map<String, LabelSinkRule> rows = new HashMap<>();

        Optional<LabelSinkRule> put(String ws, SinkKind kind, String ref, String maxLabel, String onExceed) {
            LabelSinkRule r = new LabelSinkRule(ws, kind, ref, maxLabel, onExceed);
            rows.put(ws + "/" + kind + "/" + ref, r);
            return Optional.of(r);
        }

        @Override
        public Optional<LabelSinkRule> find(String workspaceId, SinkKind kind, String ref) {
            return Optional.ofNullable(rows.get(workspaceId + "/" + kind + "/" + ref));
        }

        @Override
        public void save(LabelSinkRule rule) {
            rows.put(rule.workspaceId() + "/" + rule.kind() + "/" + rule.ref(), rule);
        }
    }

    private static DefaultSinkGuard guard(InMemoryRepo repo, PrincipalHolder holder) {
        return new DefaultSinkGuard(repo, holder,
                "INTERNAL", "CONFIDENTIAL", "INTERNAL", "INTERNAL",
                "INTERNAL", "INTERNAL", "INTERNAL", "INTERNAL");
    }

    // ---------- 四 verdict ----------
    @Test
    void fourVerdicts() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = guard(repo, holder(WS));

        // PASS：label 不超限（CONFIDENTIAL ≤ CONFIDENTIAL）
        repo.put(WS, SinkKind.IM, "grp_a", "CONFIDENTIAL", "DENY");
        assertEquals(SinkCheckResult.SinkVerdict.PASS,
                guard.check(SinkKind.IM, "grp_a", DataLabel.CONFIDENTIAL, "hello").verdict());

        // DENY：超限且 on_exceed=DENY
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.IM, "grp_a", DataLabel.SECRET, "hello").verdict());

        // REDACT_THEN_DENY：脱敏后无 PII 命中 → REDACTED（实现语义：脱敏即放行）
        repo.put(WS, SinkKind.IM, "grp_b", "INTERNAL", "REDACT_THEN_DENY");
        assertEquals(SinkCheckResult.SinkVerdict.REDACTED,
                guard.check(SinkKind.IM, "grp_b", DataLabel.CONFIDENTIAL, "无敏感内容").verdict());

        // REDACT_THEN_APPROVE：脱敏后仍含 PII（identity redactor 模拟残留）→ REQUIRE_APPROVAL
        repo.put(WS, SinkKind.IM, "grp_c", "INTERNAL", "REDACT_THEN_APPROVE");
        DefaultSinkGuard passthrough = new DefaultSinkGuard(repo, holder(WS),
                s -> s, Map.of(SinkKind.IM, DataLabel.INTERNAL));
        assertEquals(SinkCheckResult.SinkVerdict.REQUIRE_APPROVAL,
                passthrough.check(SinkKind.IM, "grp_c", DataLabel.CONFIDENTIAL,
                        "邮箱 a@b.com").verdict());
    }

    // ---------- REDACT 脱敏放行 ----------
    @Test
    void redactPathReleasesCleanPayload() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = guard(repo, holder(WS));
        repo.put(WS, SinkKind.MAIL, "*", "INTERNAL", "REDACT_THEN_DENY");

        String payload = "客户电话 13812345678，邮箱 zhang@example.com。";
        SinkCheckResult r = guard.check(SinkKind.MAIL, "smtp_1", DataLabel.CONFIDENTIAL, payload);
        assertEquals(SinkCheckResult.SinkVerdict.REDACTED, r.verdict());
        assertNotNull(r.redactedPayload());
        String out = String.valueOf(r.redactedPayload());
        assertTrue(out.contains("[已脱敏]"));
        assertEquals(2, out.split("\\[已脱敏]", -1).length - 1);
        assertTrue(r.reason().contains("脱敏"));
    }

    @Test
    void redactThenApproveWithPiiReleasesRedacted() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = guard(repo, holder(WS));
        repo.put(WS, SinkKind.SMS, "*", "INTERNAL", "REDACT_THEN_APPROVE");

        SinkCheckResult r = guard.check(SinkKind.SMS, "sms_1", DataLabel.CONFIDENTIAL,
                "验证码已发送到 18912345678");
        assertEquals(SinkCheckResult.SinkVerdict.REDACTED, r.verdict());
        assertEquals("验证码已发送到 [已脱敏]", r.redactedPayload());
    }

    // ---------- 脱敏后仍敏感：可注入 redactor 验证回落 ----------
    @Test
    void redactFallbackToDenyWhenStillSensitive() {
        InMemoryRepo repo = new InMemoryRepo();
        // redactor 什么都不做（模拟脱敏器失效/残留敏感内容）
        DefaultSinkGuard guard = new DefaultSinkGuard(repo, holder(WS),
                s -> s,
                Map.of(SinkKind.MCP, DataLabel.INTERNAL));
        repo.put(WS, SinkKind.MCP, "*", "INTERNAL", "REDACT_THEN_DENY");

        // 脱敏后仍含 PII 命中 → DENY
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.MCP, "srv_1", DataLabel.CONFIDENTIAL,
                        "卡号 4111111111111111").verdict());
    }

    @Test
    void redactFallbackToApproveWhenStillSensitive() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = new DefaultSinkGuard(repo, holder(WS),
                s -> s,
                Map.of(SinkKind.BIZ, DataLabel.INTERNAL));
        repo.put(WS, SinkKind.BIZ, "crm", "INTERNAL", "REDACT_THEN_APPROVE");

        assertEquals(SinkCheckResult.SinkVerdict.REQUIRE_APPROVAL,
                guard.check(SinkKind.BIZ, "crm", DataLabel.CONFIDENTIAL,
                        "邮箱 a@b.com").verdict());
    }

    // ---------- 缺省规则（M3-02 原文） ----------
    @Test
    void defaultRulesWhenNoRow() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = guard(repo, holder(WS));

        // IM 缺省 CONFIDENTIAL：CONFIDENTIAL 放行、SECRET 拒
        assertEquals(SinkCheckResult.SinkVerdict.PASS,
                guard.check(SinkKind.IM, "grp_x", DataLabel.CONFIDENTIAL, "hi").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.IM, "grp_x", DataLabel.SECRET, "hi").verdict());

        // MODEL_PLAN / MAIL / SMS / MCP / BIZ / FILE_EXPORT / OTHER 缺省 INTERNAL
        assertEquals(SinkCheckResult.SinkVerdict.PASS,
                guard.check(SinkKind.MODEL_PLAN, "*", DataLabel.INTERNAL, "plan").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.MODEL_PLAN, "*", DataLabel.CONFIDENTIAL, "plan").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.MAIL, "*", DataLabel.CONFIDENTIAL, "hi").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.SMS, "*", DataLabel.CONFIDENTIAL, "hi").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.MCP, "*", DataLabel.CONFIDENTIAL, "hi").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.BIZ, "*", DataLabel.CONFIDENTIAL, "hi").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.FILE_EXPORT, "*", DataLabel.CONFIDENTIAL, "hi").verdict());
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.OTHER, "*", DataLabel.CONFIDENTIAL, "hi").verdict());
    }

    // ---------- ref 级 → 通配 → 缺省 回落链 ----------
    @Test
    void exactThenWildcardThenDefault() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = guard(repo, holder(WS));

        // 通配行：IM * → CONFIDENTIAL/REDACT_THEN_APPROVE；精确行 grp_a 覆盖为 INTERNAL/DENY
        repo.put(WS, SinkKind.IM, "*", "CONFIDENTIAL", "REDACT_THEN_APPROVE");
        repo.put(WS, SinkKind.IM, "grp_a", "INTERNAL", "DENY");

        // 精确命中：grp_a 用精确行（CONFIDENTIAL > INTERNAL → DENY）
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.IM, "grp_a", DataLabel.CONFIDENTIAL, "x").verdict());
        // 精确未命中回落通配：grp_b INTERNAL ≤ CONFIDENTIAL 放行
        assertEquals(SinkCheckResult.SinkVerdict.PASS,
                guard.check(SinkKind.IM, "grp_b", DataLabel.INTERNAL, "x").verdict());
        // 通配命中且超限（SECRET > CONFIDENTIAL）→ REDACT_THEN_APPROVE 路径
        // （payload 无 PII，脱敏后无命中 → REDACTED）
        assertEquals(SinkCheckResult.SinkVerdict.REDACTED,
                guard.check(SinkKind.IM, "grp_b", DataLabel.SECRET, "x").verdict());
        // 其他 kind 无任何行 → 缺省
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.MAIL, "smtp_9", DataLabel.CONFIDENTIAL, "x").verdict());
    }

    @Test
    void noPrincipalFallsBackToDefaults() {
        InMemoryRepo repo = new InMemoryRepo();
        PrincipalHolder anonymous = new PrincipalHolder() {
            @Override public com.tioka.framework.security.Principal current() {
                throw new com.tioka.framework.error.BizException(
                        com.tioka.framework.error.ErrorCode.UNAUTHORIZED, "匿名");
            }
            @Override public Optional<com.tioka.framework.security.Principal> find() {
                return Optional.empty();
            }
            @Override public <T> T runAs(com.tioka.framework.security.Principal p,
                                        java.util.function.Supplier<T> body) { return body.get(); }
        };
        DefaultSinkGuard guard = guard(repo, anonymous);
        repo.put("ws_other", SinkKind.IM, "*", "SECRET", "REDACT_THEN_APPROVE");

        // 无 workspaceId：不查任何行，走缺省
        assertEquals(SinkCheckResult.SinkVerdict.DENY,
                guard.check(SinkKind.IM, "grp_a", DataLabel.SECRET, "x").verdict());
    }

    @Test
    void nullLabelPasses() {
        InMemoryRepo repo = new InMemoryRepo();
        DefaultSinkGuard guard = guard(repo, holder(WS));
        SinkCheckResult r = guard.check(SinkKind.IM, "grp_a", null, "x");
        assertEquals(SinkCheckResult.SinkVerdict.PASS, r.verdict());
        assertNull(r.redactedPayload());
    }
}
