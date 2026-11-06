package com.tioka.framework.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PrincipalHolderTest {
    private final SecurityContextPrincipalHolder holder = new SecurityContextPrincipalHolder();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void runAsRestores() {
        Principal outer = new Principal("1", "ws_a", PrincipalType.USER, Set.of("member"), Set.of("chat:use"), "password");
        Principal inner = new Principal("2", "ws_b", PrincipalType.USER, Set.of("builder"), Set.of("agent:write"), "password");
        holder.runAs(outer, () -> {
            assertEquals("1", holder.current().userId());
            holder.runAs(inner, () -> assertEquals("2", holder.current().userId()));
            assertEquals("1", holder.current().userId());
            return null;
        });
        assertTrue(holder.find().isEmpty());
    }
}
