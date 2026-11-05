package com.tioka.system.application.auth;

import com.tioka.system.infrastructure.SysRolePermissionRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Service
public class RolePermissionService {
    private final SysRolePermissionRepository repository;

    public RolePermissionService(SysRolePermissionRepository repository) {
        this.repository = repository;
    }

    public Set<String> expand(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String role : roles) {
            out.addAll(repository.permissionsOfRole(role));
        }
        return out;
    }
}
