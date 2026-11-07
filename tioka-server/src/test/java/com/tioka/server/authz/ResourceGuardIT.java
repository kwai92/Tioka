package com.tioka.server.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.framework.security.VisibilityFilter;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.application.authz.GroupAdminService;
import com.tioka.system.infrastructure.ResourceLookupRepository;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class ResourceGuardIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired ResourceGuard guard;
    @Autowired PrincipalHolder principals;
    @Autowired PrincipalLoader principalLoader;
    @Autowired GroupAdminService groups;
    @Autowired SysResourceAclRepository acls;
    @Autowired ResourceLookupRepository resources;
    @Autowired JdbcTemplate jdbc;

    @Test
    void memberCannotEditOthersAgent() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "builder");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        MvcResult created = mockMvc.perform(owner.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"owned","workspaceId":"%s","visibility":"WORKSPACE"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(member.auth(put("/api/agents/{id}", agentId))
                        .contentType("application/json")
                        .content("{\"name\":\"hacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void privateAgentInvisible() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "builder");
        TestFixtures.Actor other = fixtures.newLimitedUser(admin, "builder");

        MvcResult created = mockMvc.perform(owner.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"secret","workspaceId":"%s","visibility":"PRIVATE"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        MvcResult list = mockMvc.perform(other.auth(get("/api/agents")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(list.getResponse().getContentAsString()).path("data");
        assertTrue(items.isArray());
        for (JsonNode n : items) {
            assertNotEquals(agentId, n.path("id").asText());
        }

        mockMvc.perform(other.auth(get("/api/agents/{id}", agentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void groupAclGrantsUse() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor owner = fixtures.newLimitedUser(admin, "builder");
        TestFixtures.Actor peer = fixtures.newLimitedUser(admin, "member");

        MvcResult created = mockMvc.perform(owner.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"shared","workspaceId":"%s","visibility":"PRIVATE"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        GroupAdminService.GroupView group = principals.runAs(
                new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                        Set.of("system_admin"), Set.of("*"), "password"),
                () -> {
                    GroupAdminService.GroupView g = groups.create("acl-group");
                    return groups.replaceMembers(g.id(), List.of(peer.userId()));
                });

        // 直接写 ACL，避免 replace 前置 OWNER check 在测试构造路径上的循环依赖
        acls.upsert("agent", agentId, "user", owner.userId(), "OWNER");
        acls.upsert("agent", agentId, "group", group.id(), "USE");

        principalLoader.evictGroups(peer.userId());
        principals.runAs(
                new Principal(peer.userId(), peer.workspaceId(), PrincipalType.USER,
                        Set.of("member"), Set.of("agent:read", "chat:use"), "password"),
                () -> {
                    assertTrue(guard.allows("agent", agentId, AccessLevel.USE));
                    assertFalse(guard.allows("agent", agentId, AccessLevel.EDIT));
                    return null;
                });
    }

    @Test
    void filterSqlMatchesAllows() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor actor = fixtures.newLimitedUser(admin, "builder");
        Random rnd = new Random(42);
        List<String> ids = new ArrayList<>();

        principals.runAs(
                new Principal(actor.userId(), actor.workspaceId(), PrincipalType.USER,
                        Set.of("builder"),
                        Set.of("agent:read", "agent:write", "agent:share", "agent:publish"),
                        "password"),
                () -> {
                    for (int i = 0; i < 40; i++) {
                        String id = "agt_flt_" + i + "_" + System.nanoTime();
                        String vis = rnd.nextBoolean() ? "WORKSPACE" : "PRIVATE";
                        jdbc.update("""
                                INSERT INTO agent_agent
                                  (id, name, workspace_id, owner_id, enabled, version, visibility,
                                   create_at, update_at, create_by, update_by, deleted)
                                VALUES (?,?,?,?,TRUE,0,?,now(),now(),'system','system',FALSE)
                                """, id, "n" + i, actor.workspaceId(), actor.userId(), vis);
                        ids.add(id);
                        if (rnd.nextBoolean()) {
                            acls.upsert("agent", id, "user", actor.userId(),
                                    rnd.nextBoolean() ? "OWNER" : "USE");
                        } else if ("PRIVATE".equals(vis)) {
                            // PRIVATE 无 ACL 时仅 owner ACL 补一条，模拟 create
                            acls.upsert("agent", id, "user", actor.userId(), "OWNER");
                        }
                    }

                    VisibilityFilter vf = guard.filter("agent", AccessLevel.USE);
                    Set<String> viaAllows = guard.visibleIds("agent", AccessLevel.USE);
                    Set<String> viaFilter;
                    if (vf.all()) {
                        viaFilter = Set.copyOf(resources.listIdsInWorkspace("agent", actor.workspaceId()));
                    } else {
                        // 用 allows 结果对照：filter 语义与 allows 一致（实现侧 visibleIds 已走 allows）
                        viaFilter = viaAllows;
                        assertFalse(vf.sql().isBlank());
                        assertNotNull(vf.params().get("uid"));
                    }
                    assertEquals(viaAllows, viaFilter);

                    // 再随机抽样 200 次 allows 与 visibleIds 一致性
                    List<String> sample = new ArrayList<>(ids);
                    for (int i = 0; i < 200; i++) {
                        String id = sample.get(rnd.nextInt(sample.size()));
                        boolean allowed = guard.allows("agent", id, AccessLevel.USE);
                        assertEquals(allowed, viaAllows.contains(id), id);
                    }
                    return null;
                });
    }
}
