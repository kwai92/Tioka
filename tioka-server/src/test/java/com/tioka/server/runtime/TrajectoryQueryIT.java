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
 * HCI-07b：轨迹按 Turn / 步 / 用量查询（M2-04）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class TrajectoryQueryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void stepsGroupedWithUsage() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        Seed seed = seedThreadTurn(actor, "chat", 2);

        insertItem(seed.threadId, seed.turnId, 1, 1, "message", "assistant",
                "{\"text\":\"step1\"}", false);
        insertItem(seed.threadId, seed.turnId, 2, 2, "tool_call", "assistant",
                "{\"name\":\"echo\",\"args\":{\"q\":1}}", false);
        insertItem(seed.threadId, seed.turnId, 3, 2, "tool_result", "tool",
                "{\"ok\":true,\"output\":\"ok\"}", false);
        insertUsage(actor, seed.threadId, seed.turnId, 1, "gpt-a", 10, 5, 1000L, 20, "SUCCESS");
        insertUsage(actor, seed.threadId, seed.turnId, 2, "gpt-b", 20, 8, 2000L, 40, "SUCCESS");

        MvcResult result = mockMvc.perform(actor.auth(
                        get("/api/runtime/turns/{id}/trajectory", seed.turnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        JsonNode steps = data.path("steps");
        assertTrue(steps.isArray());
        assertEquals(2, steps.size());
        assertEquals(1, steps.get(0).path("step").asInt());
        assertEquals("gpt-a", steps.get(0).path("modelRef").asText());
        assertEquals(10, steps.get(0).path("promptTokens").asInt());
        assertEquals(1, steps.get(0).path("items").size());
        assertEquals(2, steps.get(1).path("step").asInt());
        assertEquals(2, steps.get(1).path("items").size());
        assertEquals(20, steps.get(1).path("promptTokens").asInt());
    }

    @Test
    void discardedIncluded() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        Seed seed = seedThreadTurn(actor, "chat", 1);
        insertItem(seed.threadId, seed.turnId, 1, 1, "message", "assistant",
                "{\"text\":\"live\"}", false);
        insertItem(seed.threadId, seed.turnId, 2, 1, "message", "assistant",
                "{\"text\":\"dead\"}", true);
        insertUsage(actor, seed.threadId, seed.turnId, 1, "gpt-a", 3, 1, 100L, 5, "FAILOVER");

        MvcResult result = mockMvc.perform(actor.auth(
                        get("/api/runtime/turns/{id}/trajectory", seed.turnId)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode steps = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("steps");
        assertEquals(1, steps.size());
        assertEquals(2, steps.get(0).path("items").size());
        assertTrue(steps.get(0).path("failover").asBoolean());
        boolean sawDiscarded = false;
        for (JsonNode item : steps.get(0).path("items")) {
            if (item.path("content").path("discarded").asBoolean(false)
                    || item.path("discarded").asBoolean(false)) {
                sawDiscarded = true;
            }
        }
        assertTrue(sawDiscarded, "作废 item 须出现且可识别");
    }

    @Test
    void orchTurnRequiresOrchRead() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");
        Seed seed = seedThreadTurn(admin, "orch", 1);
        // 把 owner 设为 member，便于成员可见 thread；orch 权限仍不足
        jdbc.update("UPDATE runtime_thread SET owner_id = ? WHERE id = ?",
                member.userId(), seed.threadId);
        insertItem(seed.threadId, seed.turnId, 1, 1, "message", "assistant",
                "{\"text\":\"orch\"}", false);

        mockMvc.perform(member.auth(get("/api/runtime/turns/{id}/trajectory", seed.turnId)))
                .andExpect(status().isForbidden());

        mockMvc.perform(admin.auth(get("/api/runtime/turns/{id}/trajectory", seed.turnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void turnsPage() throws Exception {
        TestFixtures.Actor actor = fixtures.newActor("workspace_admin");
        Seed a = seedThreadTurn(actor, "chat", 1);
        String turn2 = Ids.newId("turn");
        jdbc.update("""
                INSERT INTO runtime_turn(
                  id, thread_id, seq, status, origin, client_msg_id, run_as_json,
                  step_count, lease_version, prompt_tokens, completion_tokens,
                  create_at, update_at, create_by, update_by)
                VALUES (?, ?, 2, 'COMPLETED', 'chat', ?, ?, 1, 0, 11, 7,
                        now(), now(), 'system', 'system')
                """, turn2, a.threadId, Ids.newId("cm"),
                "{\"userId\":\"" + actor.userId() + "\",\"type\":\"USER\"}");

        MvcResult result = mockMvc.perform(actor.auth(
                        get("/api/runtime/threads/{id}/turns", a.threadId))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertTrue(data.isArray());
        assertTrue(data.size() >= 2);
    }

    private Seed seedThreadTurn(TestFixtures.Actor actor, String origin, int stepCount) {
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");
        jdbc.update("""
                INSERT INTO runtime_thread(
                  id, status, agent_version_id, workspace_id, owner_id, last_item_seq,
                  origin, depth, create_at, update_at, create_by, update_by)
                VALUES (?, 'OPEN', 'agent_v_it', ?, ?, 0, ?, 0, now(), now(), ?, ?)
                """, threadId, actor.workspaceId(), actor.userId(), origin,
                actor.userId(), actor.userId());
        jdbc.update("""
                INSERT INTO runtime_turn(
                  id, thread_id, seq, status, origin, client_msg_id, run_as_json,
                  step_count, lease_version, create_at, update_at, create_by, update_by)
                VALUES (?, ?, 1, 'COMPLETED', ?, ?, ?, ?, 0, now(), now(), ?, ?)
                """, turnId, threadId, origin, Ids.newId("cm"),
                "{\"userId\":\"" + actor.userId() + "\",\"type\":\"USER\"}",
                stepCount, actor.userId(), actor.userId());
        return new Seed(threadId, turnId);
    }

    private void insertItem(String threadId, String turnId, long seq, int step,
                            String type, String role, String contentJson, boolean discarded) {
        jdbc.update("""
                INSERT INTO runtime_item(
                  thread_id, seq, id, turn_id, step, type, role, content_json, discarded, create_at, create_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 'system')
                """, threadId, seq, Ids.newId("itm"), turnId, step, type, role, contentJson, discarded);
        jdbc.update("UPDATE runtime_thread SET last_item_seq = GREATEST(last_item_seq, ?) WHERE id = ?",
                seq, threadId);
    }

    private void insertUsage(TestFixtures.Actor actor, String threadId, String turnId, int step,
                             String modelRef, int prompt, int completion, long cost, int latency,
                             String status) {
        jdbc.update("""
                INSERT INTO model_usage(
                  id, workspace_id, user_id, thread_id, turn_id, purpose, binding_id, model_ref,
                  prompt_tokens, completion_tokens, cost_micros, latency_ms, status, step,
                  create_at, create_by)
                VALUES (?, ?, ?, ?, ?, 'CHAT', 'bind-it', ?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, Ids.newId("usage"), actor.workspaceId(), actor.userId(), threadId, turnId,
                modelRef, prompt, completion, cost, latency, status, step, actor.userId());
    }

    private record Seed(String threadId, String turnId) {}
}
