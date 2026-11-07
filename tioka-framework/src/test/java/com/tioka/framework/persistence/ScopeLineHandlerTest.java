package com.tioka.framework.persistence;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SecurityContextPrincipalHolder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeLineHandlerTest {

    @Test
    void systemWithoutWorkspaceBypassesEverything() {
        SecurityContextPrincipalHolder holder = new SecurityContextPrincipalHolder();
        ScopeLineHandler h = handler(holder, List.of("auth_user"));

        holder.runAs(system(null), () -> {
            assertTrue(h.ignoreTable("orch_run"));
            assertTrue(h.ignoreTable("auth_user"));
        });
    }

    @Test
    void systemWithWorkspaceRespectsExempt() {
        SecurityContextPrincipalHolder holder = new SecurityContextPrincipalHolder();
        ScopeLineHandler h = handler(holder, List.of("auth_user"));

        holder.runAs(system("ws_a"), () -> {
            assertFalse(h.ignoreTable("orch_run"));
            assertTrue(h.ignoreTable("auth_user"));
        });
    }

    @Test
    void userFiltersNonExemptOnly() {
        SecurityContextPrincipalHolder holder = new SecurityContextPrincipalHolder();
        ScopeLineHandler h = handler(holder, List.of("auth_user"));

        holder.runAs(user("ws_a"), () -> {
            assertFalse(h.ignoreTable("orch_run"));
            assertTrue(h.ignoreTable("auth_user"));
        });
    }

    private static ScopeLineHandler handler(SecurityContextPrincipalHolder holder, List<String> exempt) {
        PersistenceProperties props = new PersistenceProperties();
        props.setWorkspaceScopeExemptTables(exempt);
        return new ScopeLineHandler("workspace_id", new ScopedTables(props), holder);
    }

    private static Principal system(String ws) {
        return new Principal("0", ws, PrincipalType.SYSTEM, Set.of(), Set.of("*"), "system");
    }

    private static Principal user(String ws) {
        return new Principal("10", ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}
