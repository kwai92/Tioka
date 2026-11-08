package com.tioka.server.system;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.messaging.infrastructure.mapper.SysOutboxMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.event.MemberRemovedEvent;
import com.tioka.system.api.mq.SystemMqTopics;
import com.tioka.system.application.UserAdminService;
import com.tioka.system.application.WorkspaceMemberService;
import com.tioka.system.infrastructure.SysMemberRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ID-BR / PK-08：用户禁用与成员移除经 outbox 发布身份事件（与业务同事务）。
 */
@SpringBootTest
@Tag("it")
class IdentityEventBridgeIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
    }

    @Autowired TestFixtures fixtures;
    @Autowired UserAdminService userAdmin;
    @Autowired WorkspaceMemberService members;
    @Autowired SysMemberRepository memberRepo;
    @Autowired PrincipalHolder principals;
    @Autowired SysOutboxMapper outboxMapper;

    @Test
    void disablePublishesUserDisabledOutbox() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "idbr-dis-" + suffix;

        UserAdminService.CreatedUser created = principals.runAs(systemAdmin(admin), () ->
                userAdmin.create(username, "IdBrDisable", "IdBrPass!23456"));
        memberRepo.upsert(admin.workspaceId(), created.id(), "member");

        principals.runAs(systemAdmin(admin), () -> {
            userAdmin.disable(created.id());
            return null;
        });

        List<SysOutboxDO> rows = outboxMapper.selectList(Wrappers.<SysOutboxDO>lambdaQuery()
                .eq(SysOutboxDO::getTopic, SystemMqTopics.TOPIC)
                .eq(SysOutboxDO::getTag, SystemMqTopics.USER_DISABLED)
                .like(SysOutboxDO::getPayloadJson, created.id()));
        assertFalse(rows.isEmpty(), "禁用后 sys_outbox 应有 user_disabled");
        assertTrue(rows.get(0).getPayloadJson().contains(created.id()));
        assertTrue(rows.get(0).getPayloadJson().contains("\"at\""));
    }

    @Test
    void removeMemberPublishesMemberRemovedOutbox() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "idbr-rm-" + suffix;

        UserAdminService.CreatedUser created = principals.runAs(systemAdmin(admin), () ->
                userAdmin.create(username, "IdBrRemove", "IdBrPass!23456"));
        memberRepo.upsert(admin.workspaceId(), created.id(), "member");

        principals.runAs(workspaceAdmin(admin), () -> {
            members.remove(admin.workspaceId(), created.id());
            return null;
        });

        List<SysOutboxDO> rows = outboxMapper.selectList(Wrappers.<SysOutboxDO>lambdaQuery()
                .eq(SysOutboxDO::getTopic, SystemMqTopics.TOPIC)
                .eq(SysOutboxDO::getTag, SystemMqTopics.MEMBER_REMOVED)
                .like(SysOutboxDO::getPayloadJson, created.id()));
        assertEquals(1, rows.size(), "移除后应有一条 member_removed");
        String payload = rows.get(0).getPayloadJson();
        assertTrue(payload.contains(admin.workspaceId()));
        assertTrue(payload.contains(created.id()));
        assertTrue(payload.contains(MemberRemovedEvent.SOURCE_MANUAL));
    }

    private static Principal systemAdmin(TestFixtures.Actor admin) {
        return new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("system_admin"), Set.of("*"), "password");
    }

    private static Principal workspaceAdmin(TestFixtures.Actor admin) {
        return new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), Set.of("workspace:manage", "workspace:read"), "password");
    }
}
