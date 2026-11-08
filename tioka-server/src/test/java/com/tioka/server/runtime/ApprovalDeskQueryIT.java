package com.tioka.server.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HCI-05b：审批台三类主体查询（M2-04）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class ApprovalDeskQueryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void assignedToMeIncludesRoleExpansion() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor assignee = fixtures.newLimitedUser(admin, "member");

        String turnApproval = insertApproval(admin.workspaceId(), "turn", Ids.newId("subj"),
                "PENDING", "requester-x", "WRITE", null, "工具审批-turn");
        String orchApproval = insertApproval(admin.workspaceId(), "orch_run", Ids.newId("orun"),
                "PENDING", "requester-x", "WRITE", null, "编排审批");
        String flowApproval = insertApproval(admin.workspaceId(), "flow_node_run", Ids.newId("fnr"),
                "PENDING", "requester-x", "EXTERNAL_SEND", null, "Flow 人工节点");
        jdbc.update("INSERT INTO runtime_approval_assignee(approval_id, user_id) VALUES (?,?)",
                turnApproval, assignee.userId());
        jdbc.update("INSERT INTO runtime_approval_assignee(approval_id, user_id) VALUES (?,?)",
                orchApproval, assignee.userId());
        jdbc.update("INSERT INTO runtime_approval_assignee(approval_id, user_id) VALUES (?,?)",
                flowApproval, assignee.userId());

        MvcResult result = mockMvc.perform(assignee.auth(get("/api/runtime/approvals"))
                        .param("scope", "ASSIGNED_TO_ME")
                        .param("status", "PENDING")
                        .param("page", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertTrue(data.path("total").asLong() >= 3);
        JsonNode items = data.path("items");
        assertTrue(items.isArray() && items.size() >= 3);

        boolean sawTurn = false, sawOrch = false, sawFlow = false;
        for (JsonNode row : items) {
            String type = row.path("subjectType").asText();
            if ("TURN".equalsIgnoreCase(type)) {
                type = "turn";
            } else if ("ORCH_RUN".equalsIgnoreCase(type)) {
                type = "orch_run";
            } else if ("FLOW_NODE_RUN".equalsIgnoreCase(type)) {
                type = "flow_node_run";
            }
            JsonNode link = row.path("link");
            assertFalse(link.isMissingNode() || link.isNull(), "三类主体须有 link");
            assertEquals(type, link.path("type").asText());
            switch (type) {
                case "turn" -> sawTurn = true;
                case "orch_run" -> sawOrch = true;
                case "flow_node_run" -> sawFlow = true;
                default -> {
                }
            }
        }
        assertTrue(sawTurn && sawOrch && sawFlow, "应覆盖 turn / orch_run / flow_node_run");
    }

    @Test
    void requestedByMeAndWorkspaceAll() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor other = fixtures.newLimitedUser(admin, "member");

        String mine = insertApproval(admin.workspaceId(), "turn", Ids.newId("subj"),
                "PENDING", admin.userId(), "WRITE", null, "我发起的");
        insertApproval(admin.workspaceId(), "turn", Ids.newId("subj"),
                "PENDING", other.userId(), "WRITE", null, "别人发起的");
        jdbc.update("INSERT INTO runtime_approval_assignee(approval_id, user_id) VALUES (?,?)",
                mine, admin.userId());

        MvcResult mineResult = mockMvc.perform(admin.auth(get("/api/runtime/approvals"))
                        .param("scope", "REQUESTED_BY_ME")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertTrue(objectMapper.readTree(mineResult.getResponse().getContentAsString())
                .path("data").path("total").asLong() >= 1);

        MvcResult allResult = mockMvc.perform(admin.auth(get("/api/runtime/approvals"))
                        .param("scope", "WORKSPACE_ALL")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertTrue(objectMapper.readTree(allResult.getResponse().getContentAsString())
                .path("data").path("total").asLong() >= 2);

        mockMvc.perform(other.auth(get("/api/runtime/approvals"))
                        .param("scope", "WORKSPACE_ALL")
                        .param("status", "PENDING"))
                .andExpect(status().isForbidden());
    }

    @Test
    void countPendingAssigned() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor assignee = fixtures.newLimitedUser(admin, "member");
        String id = insertApproval(admin.workspaceId(), "turn", Ids.newId("subj"),
                "PENDING", "req", "WRITE", null, "角标");
        jdbc.update("INSERT INTO runtime_approval_assignee(approval_id, user_id) VALUES (?,?)",
                id, assignee.userId());

        MvcResult countResult = mockMvc.perform(assignee.auth(get("/api/runtime/approvals/count"))
                        .param("scope", "ASSIGNED_TO_ME")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertTrue(objectMapper.readTree(countResult.getResponse().getContentAsString())
                .path("data").asLong() >= 1);
    }

    private String insertApproval(String workspaceId, String subjectType, String subjectId,
                                  String status, String requestedBy, String risk, String agentId,
                                  String title) {
        String id = Ids.newId("apv");
        jdbc.update("""
                INSERT INTO runtime_approval(
                  id, workspace_id, subject_type, subject_id, kind, tool_name, status,
                  requested_by, title, risk, agent_id, assignee_json,
                  create_at, update_at, create_by, update_by)
                VALUES (?,?,?,?, 'tool', 'echo', ?, ?, ?, ?, ?, '{"userIds":[],"roleCodes":[]}',
                        now(), now(), ?, ?)
                """,
                id, workspaceId, subjectType, subjectId, status,
                requestedBy, title, risk, agentId,
                requestedBy == null ? "system" : requestedBy,
                requestedBy == null ? "system" : requestedBy);
        return id;
    }
}
