package com.tioka.framework.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RolePermissionExpanderTest {

    @Test
    void expandsTypeWildcard() {
        Set<String> out = RolePermissionExpander.expand(Set.of("agent:*"));
        assertTrue(out.contains(Permissions.AGENT_READ));
        assertTrue(out.contains(Permissions.AGENT_WRITE));
        assertTrue(out.contains(Permissions.AGENT_PUBLISH));
        assertTrue(out.contains(Permissions.AGENT_SHARE));
        assertFalse(out.contains(Permissions.FLOW_READ));
    }

    @Test
    void expandsActionWildcard() {
        Set<String> out = RolePermissionExpander.expand(Set.of("*:read"));
        assertTrue(out.contains(Permissions.AGENT_READ));
        assertTrue(out.contains(Permissions.FLOW_READ));
        assertTrue(out.contains(Permissions.MODEL_READ));
        assertTrue(out.contains(Permissions.AUDIT_READ));
        assertFalse(out.contains(Permissions.AGENT_WRITE));
    }

    @Test
    void starRemainsAsAll() {
        Set<String> out = RolePermissionExpander.expand(Set.of("*"));
        assertEquals(Set.of(Permissions.ALL), out);
    }

    @Test
    void concretePassthrough() {
        Set<String> out = RolePermissionExpander.expand(Set.of(Permissions.CHAT_USE, Permissions.AGENT_READ));
        assertEquals(Set.of(Permissions.CHAT_USE, Permissions.AGENT_READ), out);
    }
}
