package com.tioka.framework.security;

import java.util.Set;

public final class SystemPrincipals {
    private SystemPrincipals() {}

    public static Principal system() {
        return new Principal("0", null, PrincipalType.SYSTEM, Set.of(), Set.of("*"), "system");
    }
}
