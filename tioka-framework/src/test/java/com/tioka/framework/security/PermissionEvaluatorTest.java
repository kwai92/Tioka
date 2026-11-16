package com.tioka.framework.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 双实现的逻辑契约：本地零行为变化、接入侧 fail-closed 且不回落本地权限。 */
class PermissionEvaluatorTest {

    @Test
    void 本地实现与改造前判定逐字一致() {
        PermissionEvaluator evaluator = new LocalPermissionEvaluator();
        Principal admin = principal(Set.of(Permissions.ALL));
        assertThat(evaluator.firstMissing(admin, new String[]{Permissions.AGENT_READ})).isNull();
        assertThat(evaluator.firstMissing(admin, new String[]{"any:code"})).isNull();

        Principal viewer = principal(Set.of(Permissions.AGENT_READ));
        assertThat(evaluator.firstMissing(viewer, new String[]{Permissions.AGENT_READ})).isNull();
        assertThat(evaluator.firstMissing(viewer,
                new String[]{Permissions.AGENT_READ, Permissions.AGENT_WRITE}))
                .isEqualTo(Permissions.AGENT_WRITE);
        assertThat(evaluator.firstMissing(viewer, new String[]{})).isNull();
    }

    @Test
    void 接入模式以中台快照为唯一来源且绝不回落本地权限() {
        // 本地主体残留超管权限时，接入模式也不得用它绕过中台授权
        Principal localAdmin = principal(Set.of(Permissions.ALL));
        FoundationPermissionEvaluator evaluator = evaluator(personId -> Set.of(Permissions.AGENT_READ));
        assertThat(evaluator.firstMissing(localAdmin, new String[]{Permissions.AGENT_READ})).isNull();
        assertThat(evaluator.firstMissing(localAdmin, new String[]{Permissions.KB_WRITE}))
                .isEqualTo(Permissions.KB_WRITE);
    }

    @Test
    void 中台通配码不提升为超管() {
        FoundationPermissionEvaluator evaluator = evaluator(personId -> Set.of(Permissions.ALL));
        assertThat(evaluator.firstMissing(principal(Set.of()),
                new String[]{Permissions.AGENT_READ})).isEqualTo(Permissions.AGENT_READ);
    }

    @Test
    void 中台授予的具体码需全部覆盖声明() {
        FoundationPermissionEvaluator evaluator = evaluator(personId -> Set.of(Permissions.AGENT_READ));
        assertThat(evaluator.firstMissing(principal(Set.of()),
                new String[]{Permissions.AGENT_READ})).isNull();
        assertThat(evaluator.firstMissing(principal(Set.of()),
                new String[]{Permissions.AGENT_READ, Permissions.AGENT_WRITE}))
                .isEqualTo(Permissions.AGENT_WRITE);
    }

    @Test
    void 中台未知码与待规范化码按契约处理() {
        FoundationPermissionEvaluator evaluator = evaluator(
                personId -> Set.of("fdn.user.read", "  agent:read  "));
        assertThat(evaluator.firstMissing(principal(Set.of()),
                new String[]{Permissions.AGENT_READ})).isNull();
    }

    @Test
    void 数据源不可用一律拒绝放行() {
        assertThat(evaluator(personId -> null)
                .firstMissing(principal(Set.of(Permissions.ALL)), new String[]{Permissions.AGENT_READ}))
                .isEqualTo(Permissions.AGENT_READ);
        assertThat(evaluator(personId -> { throw new IllegalStateException("中台不可达"); })
                .firstMissing(principal(Set.of(Permissions.ALL)), new String[]{Permissions.AGENT_READ}))
                .isEqualTo(Permissions.AGENT_READ);
        assertThat(evaluator(personId -> Set.of())
                .firstMissing(principal(Set.of(Permissions.ALL)), new String[]{Permissions.AGENT_READ}))
                .isEqualTo(Permissions.AGENT_READ);
    }

    @Test
    void 未登记的声明码判为缺失而非放行() {
        FoundationPermissionEvaluator evaluator = evaluator(personId -> Set.of(Permissions.ALL));
        assertThat(evaluator.firstMissing(principal(Set.of()), new String[]{"typo:perm"}))
                .isEqualTo("typo:perm");
        assertThat(evaluator.firstMissing(principal(Set.of()), new String[]{})).isNull();
    }

    @Test
    void 人员标识按约定传给数据源() {
        AtomicReference<String> seen = new AtomicReference<>();
        FoundationPermissionEvaluator evaluator = evaluator(personId -> {
            seen.set(personId);
            return Set.of(Permissions.AGENT_READ);
        });
        evaluator.firstMissing(principal(Set.of()), new String[]{Permissions.AGENT_READ});
        assertThat(seen.get()).isEqualTo("u-1");
    }

    @Test
    void 显式超管码与别名按契约生效() throws IOException {
        ObjectNode contract = contract();
        ((ArrayNode) contract.get("superAdminCodes")).add("fdn:super_admin");
        ((ObjectNode) contract.get("aliases")).put("fdn.agent.read", "agent:read");
        PermissionCodeMapper mapper = PermissionCodeMapper.fromContract(contract);

        FoundationPermissionEvaluator superAdmin =
                new FoundationPermissionEvaluator(personId -> Set.of("fdn:super_admin"), mapper);
        assertThat(superAdmin.firstMissing(principal(Set.of()),
                new String[]{Permissions.KB_WRITE, Permissions.MODEL_ADMIN})).isNull();

        FoundationPermissionEvaluator aliased =
                new FoundationPermissionEvaluator(personId -> Set.of("fdn.agent.read"), mapper);
        assertThat(aliased.firstMissing(principal(Set.of()),
                new String[]{Permissions.AGENT_READ})).isNull();
    }

    @Test
    void 构造器拒绝缺失依赖() {
        assertThatThrownBy(() -> new FoundationPermissionEvaluator(null, PermissionCodeMapper.loadDefault(new ObjectMapper())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FoundationPermissionEvaluator(personId -> Set.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FoundationPermissionEvaluator evaluator(AuthorizationSnapshotSource source) {
        return new FoundationPermissionEvaluator(source, PermissionCodeMapper.loadDefault(new ObjectMapper()));
    }

    private static Principal principal(Set<String> permissions) {
        return new Principal("u-1", "s-1", PrincipalType.USER, Set.of(), permissions, "password");
    }

    private static ObjectNode contract() throws IOException {
        try (InputStream in = PermissionEvaluatorTest.class.getClassLoader()
                .getResourceAsStream("contracts/permission-code-mapping-v1.json")) {
            assertThat(in).isNotNull();
            return (ObjectNode) new ObjectMapper().readTree(in);
        }
    }
}
