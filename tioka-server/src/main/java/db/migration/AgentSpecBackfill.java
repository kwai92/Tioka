package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** AST-01 V124 回填核心；表名可注入以便 IT。 */
public final class AgentSpecBackfill {
    private static final Logger log = LoggerFactory.getLogger(AgentSpecBackfill.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Tables(String agent, String version, String draft, String sandbox, String mcpGrant) {
        public static Tables defaults() {
            return new Tables("agent_agent", "agent_version", "agent_draft", "agent_sandbox", "mcp_grant");
        }
    }

    private AgentSpecBackfill() {}

    public static List<String> migrate(Connection c, Tables t) throws Exception {
        if (!columnExists(c, t.version(), "instructions")) {
            log.info("AgentSpecBackfill: 旧列不存在，跳过");
            return List.of();
        }
        List<String> agentIds = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM " + t.agent() + " ORDER BY id")) {
            while (rs.next()) agentIds.add(rs.getString(1));
        }
        List<String> skipped = new ArrayList<>();
        for (String agentId : agentIds) {
            try {
                backfillAgent(c, t, agentId);
            } catch (Exception e) {
                log.warn("AgentSpecBackfill: 跳过 Agent {}：{}", agentId, e.getMessage());
                skipped.add(agentId);
            }
        }
        if (!skipped.isEmpty()) {
            log.warn("AgentSpecBackfill: 失败清单: {}", skipped);
        }
        return skipped;
    }

    private static void backfillAgent(Connection c, Tables t, String agentId) throws Exception {
        List<VersionRow> published = new ArrayList<>();
        VersionRow latestDraft = null;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, instructions, routing_plan_id, mcp_grant_ids, skill_bindings,
                       snapshot_hash, published, create_at
                  FROM %s
                 WHERE agent_id = ?
                 ORDER BY create_at ASC, id ASC
                """.formatted(t.version()))) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    VersionRow row = new VersionRow(
                            rs.getString("id"),
                            rs.getString("instructions"),
                            rs.getString("routing_plan_id"),
                            rs.getString("mcp_grant_ids"),
                            rs.getString("skill_bindings"),
                            rs.getString("snapshot_hash"),
                            rs.getBoolean("published"),
                            rs.getTimestamp("create_at"));
                    if (row.published) published.add(row);
                    else latestDraft = row;
                }
            }
        }
        String sandboxLevel = loadSandboxLevel(c, t, agentId);
        int versionNo = 0;
        String lastSpecJson = null;
        String lastVersionId = null;
        for (VersionRow row : published) {
            versionNo++;
            String specJson = buildSpecJson(c, t, row, sandboxLevel);
            String hash = sha256(specJson);
            try (PreparedStatement ups = c.prepareStatement("""
                    UPDATE %s
                       SET version_no = ?, spec_json = ?, schema_version = 1,
                           status = 'PUBLISHED',
                           published_at = COALESCE(published_at, ?),
                           published_by = COALESCE(published_by, 'system'),
                           snapshot_hash = ?
                     WHERE id = ?
                    """.formatted(t.version()))) {
                ups.setInt(1, versionNo);
                ups.setString(2, specJson);
                ups.setTimestamp(3, row.createAt == null ? Timestamp.from(Instant.now()) : row.createAt);
                ups.setString(4, hash);
                ups.setString(5, row.id);
                ups.executeUpdate();
            }
            lastSpecJson = specJson;
            lastVersionId = row.id;
        }
        String draftSpec;
        String baseVersionId;
        if (latestDraft != null) {
            draftSpec = buildSpecJson(c, t, latestDraft, sandboxLevel);
            baseVersionId = lastVersionId;
        } else if (lastSpecJson != null) {
            draftSpec = lastSpecJson;
            baseVersionId = lastVersionId;
        } else {
            return;
        }
        try (PreparedStatement ins = c.prepareStatement("""
                INSERT INTO %s (agent_id, spec_json, schema_version, revision, base_version_id, update_at, update_by)
                VALUES (?, ?, 1, 0, ?, now(), 'system')
                ON CONFLICT (agent_id) DO NOTHING
                """.formatted(t.draft()))) {
            ins.setString(1, agentId);
            ins.setString(2, draftSpec);
            ins.setString(3, baseVersionId);
            ins.executeUpdate();
        }
    }

    private static String buildSpecJson(Connection c, Tables t, VersionRow row, String sandboxLevel)
            throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        ObjectNode profile = root.putObject("profile");
        profile.put("name", row.id);
        profile.put("kind", "CHAT");
        profile.putArray("tags");
        ObjectNode instructions = root.putObject("instructions");
        ArrayNode sections = instructions.putArray("sections");
        ObjectNode sec = sections.addObject();
        sec.put("key", "main");
        sec.put("text", row.instructions == null ? "" : row.instructions);
        ObjectNode model = root.putObject("model");
        model.put("routingPlanId", row.routingPlanId == null ? "unset" : row.routingPlanId);
        model.put("allowUserOverride", false);
        ObjectNode tools = root.putObject("tools");
        tools.putArray("builtin");
        ArrayNode mcp = tools.putArray("mcp");
        for (Map<String, Object> binding : resolveMcp(c, t, row.mcpGrantIds)) {
            ObjectNode b = mcp.addObject();
            b.put("serverId", String.valueOf(binding.get("serverId")));
            ArrayNode allow = b.putArray("allowlist");
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) binding.get("allowlist");
            if (list != null) for (String x : list) allow.add(x);
        }
        tools.putArray("flows");
        tools.putArray("business");
        ArrayNode skills = root.putArray("skills");
        for (Map<String, String> sk : parseSkills(row.skillBindings)) {
            ObjectNode s = skills.addObject();
            s.put("skillVersionId", sk.get("skillId"));
            s.put("mode", "ALWAYS");
        }
        ObjectNode knowledge = root.putObject("knowledge");
        knowledge.putArray("kbIds");
        knowledge.put("mode", "TOOL");
        ObjectNode sandbox = root.putObject("sandbox");
        sandbox.put("level", sandboxLevel);
        sandbox.putArray("networkAllowlist");
        ObjectNode approval = root.putObject("approval");
        approval.putObject("toolPolicies");
        ObjectNode limits = root.putObject("limits");
        limits.put("maxSteps", 20);
        root.putNull("charter");
        root.putNull("output");
        root.putNull("subAgents");
        root.putNull("dataClearance");
        return MAPPER.writeValueAsString(root);
    }

    private static List<Map<String, Object>> resolveMcp(Connection c, Tables t, String mcpGrantIdsJson)
            throws Exception {
        List<String> ids = parseStringList(mcpGrantIdsJson);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!tableExists(c, t.mcpGrant())) return out;
        for (String grantId : ids) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT server_id, allowlist FROM " + t.mcpGrant() + " WHERE id = ? AND deleted = FALSE")) {
                ps.setString(1, grantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("mcp_grant 不存在: " + grantId);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("serverId", rs.getString(1));
                    m.put("allowlist", parseStringList(rs.getString(2)));
                    out.add(m);
                }
            }
        }
        return out;
    }

    private static String loadSandboxLevel(Connection c, Tables t, String agentId) throws Exception {
        if (!tableExists(c, t.sandbox())) return "EDIT";
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT level FROM " + t.sandbox() + " WHERE agent_id = ?")) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "EDIT";
                String raw = rs.getString(1);
                if (raw == null) return "EDIT";
                return switch (raw.toUpperCase()) {
                    case "STRICT" -> "READ";
                    case "PERMISSIVE" -> "FULL";
                    case "READ", "EDIT", "FULL" -> raw.toUpperCase();
                    default -> "EDIT";
                };
            }
        }
    }

    private static List<String> parseStringList(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        return MAPPER.readValue(json, new TypeReference<>() {});
    }

    private static List<Map<String, String>> parseSkills(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        return MAPPER.readValue(json, new TypeReference<>() {});
    }

    private static String sha256(String raw) throws Exception {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(
                md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static boolean columnExists(Connection c, String table, String column) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean tableExists(Connection c, String table) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = ?
                """)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private record VersionRow(String id, String instructions, String routingPlanId,
                              String mcpGrantIds, String skillBindings, String snapshotHash,
                              boolean published, Timestamp createAt) {}
}
