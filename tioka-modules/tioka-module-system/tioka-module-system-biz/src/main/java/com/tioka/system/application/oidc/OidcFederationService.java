package com.tioka.system.application.oidc;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.event.MemberRemovedEvent;
import com.tioka.system.application.auth.PasswordService;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.application.auth.TokenService;
import com.tioka.system.application.lifecycle.AfterCommitEvents;
import com.tioka.system.config.OidcProperties;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.SysGroupRepository;
import com.tioka.system.infrastructure.SysMemberRepository;
import com.tioka.system.infrastructure.SysUserRoleRepository;
import com.tioka.system.infrastructure.SysWorkspaceRepository;
import com.tioka.system.infrastructure.persistence.SysMemberDO;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import com.tioka.system.infrastructure.persistence.SysGroupDO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FND-05：OIDC 用户映射、组同步、group-role-mappings → sys_member / sys_user_role。
 */
@Service
public class OidcFederationService {
    private final AuthUserRepository users;
    private final PasswordService passwords;
    private final TokenService tokens;
    private final SysGroupRepository groups;
    private final SysMemberRepository members;
    private final SysUserRoleRepository userRoles;
    private final SysWorkspaceRepository workspaces;
    private final PrincipalLoader principalLoader;
    private final ApplicationEventPublisher events;
    private final AuditApi audit;

    public OidcFederationService(AuthUserRepository users,
                                 PasswordService passwords,
                                 TokenService tokens,
                                 SysGroupRepository groups,
                                 SysMemberRepository members,
                                 SysUserRoleRepository userRoles,
                                 SysWorkspaceRepository workspaces,
                                 PrincipalLoader principalLoader,
                                 ApplicationEventPublisher events,
                                 AuditApi audit) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.groups = groups;
        this.members = members;
        this.userRoles = userRoles;
        this.workspaces = workspaces;
        this.principalLoader = principalLoader;
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public TokenService.TokenPair federateAndIssue(String idp,
                                                   OidcProperties.Provider provider,
                                                   IdPAdapter.FederatedIdentity identity) {
        AuthUserDO user = resolveAndSync(idp, provider, identity);
        TokenService.TokenPair pair = tokens.issue(user.getId());
        auditAuth(user, AuditOutcome.SUCCESS, null);
        return pair;
    }

    /**
     * JWT 资源服务器 / 登录共用：解析或自动建用户，并同步组与 group-role-mappings。
     */
    @Transactional
    public AuthUserDO resolveAndSync(String idp,
                                     OidcProperties.Provider provider,
                                     IdPAdapter.FederatedIdentity identity) {
        AuthUserDO user = resolveOrProvision(idp, provider, identity);
        syncGroups(idp, user, identity.groups());
        syncRoleMappings(idp, provider, user, identity.groups());
        principalLoader.evictUser(user.getId());
        principalLoader.evictGroups(String.valueOf(user.getId()));
        return user;
    }

    private AuthUserDO resolveOrProvision(String idp,
                                          OidcProperties.Provider provider,
                                          IdPAdapter.FederatedIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "联邦身份无效");
        }
        String idpKey = "oidc:" + idp;
        AuthUserDO row = users.findByIdpAndSubject(idpKey, identity.subject());
        if (row != null) {
            if (!Boolean.TRUE.equals(row.getEnabled())) {
                throw new BizException(ErrorCode.FORBIDDEN, "账号已禁用");
            }
            boolean dirty = false;
            if (identity.displayName() != null && !identity.displayName().equals(row.getDisplayName())) {
                row.setDisplayName(identity.displayName());
                dirty = true;
            }
            if (identity.email() != null && !identity.email().equals(row.getEmail())) {
                row.setEmail(identity.email());
                dirty = true;
            }
            if (dirty) {
                users.update(row);
            }
            return row;
        }
        if (!provider.isAutoProvision()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未开通且禁止自动建号");
        }
        String username = pickUsername(idp, identity);
        AuthUserDO created = new AuthUserDO();
        created.setId(users.nextId());
        created.setUsername(username);
        created.setPasswordHash(passwords.hash(UUID.randomUUID().toString()));
        created.setDisplayName(identity.displayName() == null ? username : identity.displayName());
        created.setEnabled(true);
        created.setIdp(idpKey);
        created.setExternalSubject(identity.subject());
        created.setEmail(identity.email());
        created.setMustChangePassword(false);
        created.setDefaultWorkspaceId("ws_default");
        users.insert(created);
        return users.findById(created.getId());
    }

    private String pickUsername(String idp, IdPAdapter.FederatedIdentity identity) {
        String base = identity.preferredUsername();
        if (base == null || base.isBlank()) {
            base = identity.email();
        }
        if (base == null || base.isBlank()) {
            base = "oidc_" + identity.subject();
        }
        if (users.findByUsername(base) == null) {
            return base;
        }
        String suffixed = base + "_" + idp;
        if (users.findByUsername(suffixed) == null) {
            return suffixed;
        }
        return suffixed + "_" + users.nextId();
    }

    private void syncGroups(String idp, AuthUserDO user, List<String> groupPaths) {
        String source = "oidc:" + idp;
        String uid = String.valueOf(user.getId());
        List<String> desiredIds = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        if (groupPaths != null) {
            for (String p : groupPaths) {
                if (p != null && !p.isBlank()) {
                    paths.add(p);
                }
            }
        }
        for (String path : paths) {
            SysGroupDO g = groups.findBySourceAndExternalId(source, path);
            if (g == null) {
                Instant now = Instant.now();
                g = new SysGroupDO();
                g.setId(Ids.newId("grp"));
                g.setName(pathLeaf(path));
                g.setSource(source);
                g.setExternalId(path);
                g.setCreateAt(now);
                g.setUpdateAt(now);
                g.setCreateBy("system");
                g.setUpdateBy("system");
                groups.insert(g);
            }
            desiredIds.add(g.getId());
        }
        groups.replaceUserMembershipsForSource(uid, source, desiredIds);
    }

    private void syncRoleMappings(String idp,
                                  OidcProperties.Provider provider,
                                  AuthUserDO user,
                                  List<String> groupPaths) {
        String source = MemberRemovedEvent.oidcSource(idp);
        String uid = String.valueOf(user.getId());
        Set<String> groups = new HashSet<>();
        if (groupPaths != null) {
            groups.addAll(groupPaths);
        }

        Set<String> desiredSystemRoles = new LinkedHashSet<>();
        Set<String> desiredMemberKeys = new LinkedHashSet<>(); // workspace|role

        List<OidcProperties.GroupRoleMapping> mappings = provider.getGroupRoleMappings();
        if (mappings != null) {
            for (OidcProperties.GroupRoleMapping m : mappings) {
                if (m.getGroup() == null || !groups.contains(m.getGroup())) {
                    continue;
                }
                if (m.getSystemRole() != null && !m.getSystemRole().isBlank()) {
                    desiredSystemRoles.add(m.getSystemRole());
                }
                if (m.getWorkspace() != null && !m.getWorkspace().isBlank()
                        && m.getRole() != null && !m.getRole().isBlank()) {
                    if (workspaces.findById(m.getWorkspace()) == null) {
                        continue;
                    }
                    desiredMemberKeys.add(m.getWorkspace() + "|" + m.getRole());
                }
            }
        }

        // 系统级角色：仅管理映射表中出现过的 systemRole
        Set<String> mappedSystemRoles = new LinkedHashSet<>();
        if (mappings != null) {
            for (OidcProperties.GroupRoleMapping m : mappings) {
                if (m.getSystemRole() != null && !m.getSystemRole().isBlank()) {
                    mappedSystemRoles.add(m.getSystemRole());
                }
            }
        }
        for (String role : mappedSystemRoles) {
            if (desiredSystemRoles.contains(role)) {
                userRoles.upsert(uid, role);
            } else {
                userRoles.delete(uid, role);
            }
        }

        // 空间成员：upsert 期望行；软删本 source 下不再满足的行并发事件
        for (String key : desiredMemberKeys) {
            int sep = key.indexOf('|');
            String ws = key.substring(0, sep);
            String role = key.substring(sep + 1);
            members.upsertWithSource(ws, uid, role, source);
        }

        List<SysMemberDO> existing = members.findByUserIdAndSource(uid, source);
        for (SysMemberDO row : existing) {
            String key = row.getWorkspaceId() + "|" + row.getRoleCode();
            if (!desiredMemberKeys.contains(key)) {
                members.softDeleteOne(row.getWorkspaceId(), uid, row.getRoleCode());
                AfterCommitEvents.publish(events,
                        new MemberRemovedEvent(row.getWorkspaceId(), uid, source));
                audit.record(new AuditEvent(null, Instant.now(), row.getWorkspaceId(),
                        "system", "oidc", "member.remove", "workspace_member",
                        row.getWorkspaceId() + ":" + uid,
                        AuditOutcome.SUCCESS, null, null, null, null,
                        Map.of("userId", uid, "source", source, "role", row.getRoleCode())));
            }
        }
    }

    private void auditAuth(AuthUserDO user, AuditOutcome outcome, String reason) {
        try {
            audit.recordIndependent(AuditEvent.builder()
                    .ts(Instant.now())
                    .actorType("user")
                    .actorId(String.valueOf(user.getId()))
                    .action("auth.login")
                    .resourceType("auth")
                    .outcome(outcome)
                    .reason(reason)
                    .details(Map.of("username", user.getUsername(), "method", "oidc"))
                    .build());
        } catch (RuntimeException ignored) {
            // 审计失败不影响登录
        }
    }

    private static String pathLeaf(String path) {
        if (path == null || path.isBlank()) {
            return "group";
        }
        int i = path.lastIndexOf('/');
        if (i >= 0 && i < path.length() - 1) {
            return path.substring(i + 1);
        }
        return path;
    }
}
