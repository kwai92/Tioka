package com.tioka.system.application.authz;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RolePermissionExpander;
import com.tioka.system.infrastructure.SysRolePermissionRepository;
import com.tioka.system.infrastructure.SysRoleRepository;
import com.tioka.system.infrastructure.SysUserRoleRepository;
import com.tioka.system.infrastructure.mapper.SysRoleMapper;
import com.tioka.system.infrastructure.mapper.SysRolePermissionWriteMapper;
import com.tioka.system.infrastructure.persistence.SysRoleDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class RoleAdminService {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    private final SysRoleRepository roles;
    private final SysRolePermissionRepository rolePermissions;
    private final SysRoleMapper roleMapper;
    private final SysRolePermissionWriteMapper permissionWriter;
    private final SysUserRoleRepository userRoles;

    public RoleAdminService(SysRoleRepository roles,
                            SysRolePermissionRepository rolePermissions,
                            SysRoleMapper roleMapper,
                            SysRolePermissionWriteMapper permissionWriter,
                            SysUserRoleRepository userRoles) {
        this.roles = roles;
        this.rolePermissions = rolePermissions;
        this.roleMapper = roleMapper;
        this.permissionWriter = permissionWriter;
        this.userRoles = userRoles;
    }

    public List<RoleView> list() {
        List<RoleView> out = new ArrayList<>();
        for (SysRoleDO r : roleMapper.listAll()) {
            out.add(toView(r));
        }
        return out;
    }

    public List<String> permissionCatalog() {
        return Arrays.asList(Permissions.ALL_CONCRETE);
    }

    @Transactional
    public RoleView create(String code, String name, String scope, List<String> permissions) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "code 不符合规范");
        }
        if (roles.findByCode(code) != null) {
            throw new BizException(ErrorCode.CONFLICT, "角色已存在");
        }
        if (scope == null || (!"SYSTEM".equals(scope) && !"WORKSPACE".equals(scope))) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scope 必须是 SYSTEM 或 WORKSPACE");
        }
        permissionWriter.insertRole(code, name == null ? code : name, scope, false);
        replacePermissions(code, permissions);
        return toView(roles.findByCode(code));
    }

    @Transactional
    public RoleView update(String code, String name, List<String> permissions) {
        SysRoleDO existing = roles.findByCode(code);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new BizException(ErrorCode.FORBIDDEN, "内置角色不可改");
        }
        permissionWriter.updateRoleName(code, name == null ? existing.getName() : name);
        replacePermissions(code, permissions);
        return toView(roles.findByCode(code));
    }

    @Transactional
    public void delete(String code) {
        SysRoleDO existing = roles.findByCode(code);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        if (Boolean.TRUE.equals(existing.getBuiltin())) {
            throw new BizException(ErrorCode.FORBIDDEN, "内置角色不可删");
        }
        if (userRoles.countByRole(code) > 0 || permissionWriter.countMembersWithRole(code) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "仍有成员使用该角色");
        }
        permissionWriter.deletePermissions(code);
        permissionWriter.softDeleteRole(code);
    }

    private void replacePermissions(String code, List<String> permissions) {
        permissionWriter.deletePermissions(code);
        if (permissions == null) {
            return;
        }
        for (String raw : RolePermissionExpander.expand(permissions)) {
            permissionWriter.insertPermission(code, raw);
        }
    }

    private RoleView toView(SysRoleDO r) {
        return new RoleView(r.getCode(), r.getName(), r.getScope(),
                Boolean.TRUE.equals(r.getBuiltin()),
                rolePermissions.permissionsOfRole(r.getCode()));
    }

    public record RoleView(String code, String name, String scope, boolean builtin, List<String> permissions) {}
}
