package com.tioka.framework.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 权限码映射契约：目录一致性、通配不提升、未知码忽略、放宽策略 fail-fast。 */
class PermissionCodeMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 契约目录与权限点常量逐字一致() {
        PermissionCodeMapper mapper = PermissionCodeMapper.loadDefault(JSON);
        assertThat(mapper.registeredCodes()).containsExactlyInAnyOrder(Permissions.ALL_CONCRETE);
        assertThat(mapper.registeredCodes()).doesNotContain(Permissions.ALL);
        assertThat(mapper.registeredCodes()).hasSize(Permissions.ALL_CONCRETE.length);
        assertThat(mapper.isRegistered(Permissions.AGENT_READ)).isTrue();
        assertThat(mapper.isRegistered("typo:perm")).isFalse();
        assertThat(mapper.isRegistered(null)).isFalse();
    }

    @Test
    void 已登记权限项恒等映射且去重去空白() {
        PermissionCodeMapper mapper = PermissionCodeMapper.loadDefault(JSON);
        Set<String> mapped = mapper.map(java.util.Arrays.asList(
                "agent:read", "  kb:write  ", "agent:read", "", "   ", null));
        assertThat(mapped).containsExactlyInAnyOrder(Permissions.AGENT_READ, Permissions.KB_WRITE);
    }

    @Test
    void 中台通配码一律丢弃绝不提升为超管() {
        PermissionCodeMapper mapper = PermissionCodeMapper.loadDefault(JSON);
        for (String wildcard : new String[]{"*", "agent:*", "*:read", "agent:*:read"}) {
            assertThat(mapper.map(List.of(wildcard)))
                    .as("中台通配码 %s 不得转成 Tioka 权限", wildcard)
                    .isEmpty();
            assertThat(mapper.grantsSuperAdmin(List.of(wildcard))).isFalse();
        }
        assertThat(mapper.map(List.of("*", "agent:read")))
                .containsExactly(Permissions.AGENT_READ);
    }

    @Test
    void 未登记的中台码不授任何权限() {
        PermissionCodeMapper mapper = PermissionCodeMapper.loadDefault(JSON);
        assertThat(mapper.map(List.of("fdn.user.read", "agent:manage", "AGENT:READ"))).isEmpty();
        assertThat(mapper.map(null)).isEmpty();
        assertThat(mapper.map(List.of())).isEmpty();
    }

    @Test
    void 默认契约不允许中台直接授予超管() {
        PermissionCodeMapper mapper = PermissionCodeMapper.loadDefault(JSON);
        assertThat(mapper.grantsSuperAdmin(List.of("fdn:super_admin"))).isFalse();
        assertThat(mapper.grantsSuperAdmin(null)).isFalse();
        assertThat(mapper.grantsSuperAdmin(List.of())).isFalse();
    }

    @Test
    void 显式配置超管码后才放行且仍拒绝通配() throws IOException {
        ObjectNode contract = contract();
        ((ArrayNode) contract.get("superAdminCodes")).add("fdn:super_admin");
        PermissionCodeMapper mapper = PermissionCodeMapper.fromContract(contract);
        assertThat(mapper.grantsSuperAdmin(List.of("fdn:super_admin"))).isTrue();
        assertThat(mapper.grantsSuperAdmin(List.of("  fdn:super_admin  "))).isTrue();
        assertThat(mapper.grantsSuperAdmin(List.of("*"))).isFalse();
    }

    @Test
    void 别名必须映射到已登记的具体权限码() throws IOException {
        ObjectNode contract = contract();
        ((ObjectNode) contract.get("aliases")).put("fdn.agent.read", "agent:read");
        PermissionCodeMapper mapper = PermissionCodeMapper.fromContract(contract);
        assertThat(mapper.map(List.of("fdn.agent.read"))).containsExactly(Permissions.AGENT_READ);
    }

    @Test
    void 映射结果不可变() {
        Set<String> mapped = PermissionCodeMapper.loadDefault(JSON).map(List.of("agent:read"));
        assertThatThrownBy(() -> mapped.add("kb:write"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 放宽通配策略或版本时拒绝加载() throws IOException {
        ObjectNode relaxed = contract();
        relaxed.put("wildcardPolicy", "passthrough");
        assertThatThrownBy(() -> PermissionCodeMapper.fromContract(relaxed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcardPolicy");

        ObjectNode wrongVersion = contract();
        wrongVersion.put("version", 2);
        assertThatThrownBy(() -> PermissionCodeMapper.fromContract(wrongVersion))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("契约版本");
    }

    @Test
    void 目录含重复项或通配项时拒绝加载() throws IOException {
        ObjectNode duplicated = contract();
        ((ArrayNode) duplicated.get("tiokaPermissionCodes")).add("agent:read");
        assertThatThrownBy(() -> PermissionCodeMapper.fromContract(duplicated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复");

        ObjectNode wildcard = contract();
        ((ArrayNode) wildcard.get("tiokaPermissionCodes")).add("agent:*");
        assertThatThrownBy(() -> PermissionCodeMapper.fromContract(wildcard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("通配");
    }

    @Test
    void 别名目标未登记或超管码含通配时拒绝加载() throws IOException {
        ObjectNode badAlias = contract();
        ((ObjectNode) badAlias.get("aliases")).put("fdn.agent.read", "not:registered");
        assertThatThrownBy(() -> PermissionCodeMapper.fromContract(badAlias))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("别名目标");

        ObjectNode badSuper = contract();
        ((ArrayNode) badSuper.get("superAdminCodes")).add("*");
        assertThatThrownBy(() -> PermissionCodeMapper.fromContract(badSuper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("超管码");
    }

    private static ObjectNode contract() throws IOException {
        try (InputStream in = PermissionCodeMapperTest.class.getClassLoader()
                .getResourceAsStream("contracts/permission-code-mapping-v1.json")) {
            assertThat(in).isNotNull();
            return (ObjectNode) JSON.readTree(in);
        }
    }
}
