package com.tioka.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.support.IntegrationTestSupport;
import db.migration.AgentSpecBackfill;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class V124MigrationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void backfillsThreeShapes() throws Exception {
        dropShadow();
        createShadow();

        String a1 = "agt_draft_" + shortId();
        String av1 = "av_pub_" + shortId();
        String avDraft = "av_dr_" + shortId();
        String gr1 = "gr_" + shortId();
        jdbc.update("INSERT INTO v124_agent_agent(id,name,workspace_id,enabled) VALUES (?,?,?,TRUE)",
                a1, "a1", "ws");
        jdbc.update("INSERT INTO v124_mcp_grant(id,agent_id,server_id,allowlist) VALUES (?,?,?,?)",
                gr1, a1, "srv_1", "[\"erp.query\"]");
        jdbc.update("""
                INSERT INTO v124_agent_version(id,agent_id,instructions,routing_plan_id,mcp_grant_ids,skill_bindings,published,create_at)
                VALUES (?,?,?,?,?,?,TRUE,now() - interval '2 day')
                """, av1, a1, "pub-inst", "rp_1", "[\"" + gr1 + "\"]", "[]");
        jdbc.update("""
                INSERT INTO v124_agent_version(id,agent_id,instructions,routing_plan_id,mcp_grant_ids,skill_bindings,published,create_at)
                VALUES (?,?,?,?,?,?,FALSE,now() - interval '1 day')
                """, avDraft, a1, "draft-inst", "rp_1", "[]", "[]");
        jdbc.update("INSERT INTO v124_agent_sandbox(agent_id,level) VALUES (?,?)", a1, "STRICT");

        String a2 = "agt_pub_" + shortId();
        String av2 = "av_only_" + shortId();
        jdbc.update("INSERT INTO v124_agent_agent(id,name,workspace_id,enabled) VALUES (?,?,?,TRUE)",
                a2, "a2", "ws");
        jdbc.update("""
                INSERT INTO v124_agent_version(id,agent_id,instructions,routing_plan_id,mcp_grant_ids,skill_bindings,published)
                VALUES (?,?,?,?,?,?,TRUE)
                """, av2, a2, "only-pub", "rp_2", "[]", "[]");

        String a3 = "agt_bad_" + shortId();
        String av3 = "av_bad_" + shortId();
        jdbc.update("INSERT INTO v124_agent_agent(id,name,workspace_id,enabled) VALUES (?,?,?,TRUE)",
                a3, "a3", "ws");
        jdbc.update("""
                INSERT INTO v124_agent_version(id,agent_id,instructions,routing_plan_id,mcp_grant_ids,skill_bindings,published)
                VALUES (?,?,?,?,?,?,TRUE)
                """, av3, a3, "bad", "rp_3", "[\"gr_missing\"]", "[]");

        List<String> skipped;
        try (var conn = jdbc.getDataSource().getConnection()) {
            skipped = AgentSpecBackfill.migrate(conn, new AgentSpecBackfill.Tables(
                    "v124_agent_agent", "v124_agent_version", "v124_agent_draft",
                    "v124_agent_sandbox", "v124_mcp_grant"));
        }

        assertTrue(skipped.contains(a3));

        JsonNode a1Spec = mapper.readTree(jdbc.queryForObject(
                "SELECT spec_json FROM v124_agent_version WHERE id=?", String.class, av1));
        assertEquals("pub-inst", a1Spec.at("/instructions/sections/0/text").asText());
        assertEquals("READ", a1Spec.at("/sandbox/level").asText());
        assertEquals("srv_1", a1Spec.at("/tools/mcp/0/serverId").asText());
        assertEquals(1, jdbc.queryForObject(
                "SELECT version_no FROM v124_agent_version WHERE id=?", Integer.class, av1));
        String draftJson = jdbc.queryForObject(
                "SELECT spec_json FROM v124_agent_draft WHERE agent_id=?", String.class, a1);
        assertEquals("draft-inst", mapper.readTree(draftJson).at("/instructions/sections/0/text").asText());

        JsonNode a2Draft = mapper.readTree(jdbc.queryForObject(
                "SELECT spec_json FROM v124_agent_draft WHERE agent_id=?", String.class, a2));
        assertEquals("only-pub", a2Draft.at("/instructions/sections/0/text").asText());

        assertNull(jdbc.queryForObject(
                "SELECT version_no FROM v124_agent_version WHERE id=?", Integer.class, av3));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM v124_agent_draft WHERE agent_id=?", Integer.class, a3));
    }

    private void dropShadow() {
        jdbc.execute("DROP TABLE IF EXISTS v124_mcp_grant");
        jdbc.execute("DROP TABLE IF EXISTS v124_agent_sandbox");
        jdbc.execute("DROP TABLE IF EXISTS v124_agent_version");
        jdbc.execute("DROP TABLE IF EXISTS v124_agent_draft");
        jdbc.execute("DROP TABLE IF EXISTS v124_agent_agent");
    }

    private void createShadow() {
        jdbc.execute("""
                CREATE TABLE v124_agent_agent (
                  id VARCHAR(64) PRIMARY KEY, name VARCHAR(255) NOT NULL,
                  workspace_id VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL,
                  published_version_id VARCHAR(64),
                  create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  update_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  create_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  update_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  deleted BOOLEAN NOT NULL DEFAULT FALSE,
                  version INT NOT NULL DEFAULT 0,
                  visibility VARCHAR(32) NOT NULL DEFAULT 'WORKSPACE'
                )
                """);
        jdbc.execute("""
                CREATE TABLE v124_agent_version (
                  id VARCHAR(64) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
                  instructions TEXT, routing_plan_id VARCHAR(64) NOT NULL,
                  mcp_grant_ids TEXT, skill_bindings TEXT, snapshot_hash VARCHAR(128),
                  published BOOLEAN NOT NULL,
                  create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  create_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  version_no INT, spec_json TEXT, schema_version INT NOT NULL DEFAULT 1,
                  status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED', note VARCHAR(500),
                  published_at TIMESTAMPTZ, published_by VARCHAR(64)
                )
                """);
        jdbc.execute("""
                CREATE TABLE v124_agent_draft (
                  agent_id VARCHAR(64) PRIMARY KEY, spec_json TEXT NOT NULL,
                  schema_version INT NOT NULL DEFAULT 1, revision INT NOT NULL DEFAULT 0,
                  base_version_id VARCHAR(64),
                  update_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  update_by VARCHAR(64) NOT NULL DEFAULT 'system'
                )
                """);
        jdbc.execute("""
                CREATE TABLE v124_agent_sandbox (
                  agent_id VARCHAR(64) PRIMARY KEY, level VARCHAR(32) NOT NULL,
                  create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  update_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  create_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  update_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  deleted BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
        jdbc.execute("""
                CREATE TABLE v124_mcp_grant (
                  id VARCHAR(64) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
                  server_id VARCHAR(64) NOT NULL, allowlist TEXT,
                  create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  update_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                  create_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  update_by VARCHAR(64) NOT NULL DEFAULT 'system',
                  deleted BOOLEAN NOT NULL DEFAULT FALSE
                )
                """);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
