package db.migration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RT-02：把 runtime_session.parts_json 回填为 runtime_turn / runtime_item。
 */
public class V104__BackfillRuntimeItems extends BaseJavaMigration {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BATCH = 500;

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        if (!columnExists(c, "runtime_session", "parts_json")) {
            return;
        }
        List<String> sessionIds = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM runtime_session ORDER BY id")) {
            while (rs.next()) {
                sessionIds.add(rs.getString(1));
            }
        }
        for (int i = 0; i < sessionIds.size(); i += BATCH) {
            List<String> batch = sessionIds.subList(i, Math.min(i + BATCH, sessionIds.size()));
            for (String sessionId : batch) {
                backfillSession(c, sessionId);
            }
        }
    }

    private void backfillSession(Connection c, String sessionId) throws Exception {
        String partsJson;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT parts_json FROM runtime_session WHERE id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                partsJson = rs.getString(1);
            }
        }
        if (partsJson == null || partsJson.isBlank() || "[]".equals(partsJson.trim())) {
            return;
        }
        List<Map<String, Object>> parts = MAPPER.readValue(partsJson, new TypeReference<>() {});
        if (parts.isEmpty()) {
            return;
        }

        // 按 turnId 分组；无 turnId 的归入会话级默认 turn
        String defaultTurnId = "turn_bf_" + shortId();
        long itemSeq = 0;
        int turnSeq = 0;
        String currentTurn = null;

        for (Map<String, Object> part : parts) {
            String type = str(part.get("type"));
            if (type == null) {
                continue;
            }
            // delta 片段丢弃
            Object payload = part.get("payload");
            if (payload instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("delta"))) {
                continue;
            }
            String turnId = turnIdOf(part, defaultTurnId);
            if (!turnId.equals(currentTurn)) {
                currentTurn = turnId;
                turnSeq++;
                insertTurn(c, turnId, sessionId, turnSeq);
            }
            MappedItem mapped = mapPart(type, part);
            if (mapped == null) {
                continue;
            }
            itemSeq++;
            insertItem(c, sessionId, itemSeq, turnId, mapped);
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE runtime_session SET last_item_seq = ? WHERE id = ?")) {
            ps.setLong(1, itemSeq);
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    private static void insertTurn(Connection c, String turnId, String sessionId, int seq) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO runtime_turn (
                  id, session_id, seq, status, origin, run_as_json, step_count, lease_version,
                  create_at, update_at, create_by, update_by
                ) VALUES (?, ?, ?, 'COMPLETED', 'chat', ?, 0, 0, now(), now(), 'system', 'system')
                ON CONFLICT (id) DO NOTHING
                """)) {
            ps.setString(1, turnId);
            ps.setString(2, sessionId);
            ps.setInt(3, seq);
            ps.setString(4, "{\"tenantId\":\"default\",\"userId\":\"1\",\"type\":\"USER\"}");
            ps.executeUpdate();
        }
    }

    private static void insertItem(Connection c, String sessionId, long seq, String turnId, MappedItem m)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO runtime_item (
                  session_id, seq, id, turn_id, step, type, role, content_json, discarded, create_at, create_by
                ) VALUES (?, ?, ?, ?, 0, ?, ?, ?, FALSE, now(), 'system')
                ON CONFLICT (session_id, seq) DO NOTHING
                """)) {
            ps.setString(1, sessionId);
            ps.setLong(2, seq);
            ps.setString(3, "itm_bf_" + shortId());
            ps.setString(4, turnId);
            ps.setString(5, m.type);
            ps.setString(6, m.role);
            ps.setString(7, m.contentJson);
            ps.executeUpdate();
        }
    }

    private static MappedItem mapPart(String type, Map<String, Object> part) throws Exception {
        String text = str(part.get("text"));
        Object payload = part.get("payload");
        return switch (type) {
            case "text" -> {
                String role = roleFromPayload(payload, "assistant");
                if ("user".equals(role)) {
                    yield new MappedItem("message", "user",
                            MAPPER.writeValueAsString(Map.of("text", text == null ? "" : text)));
                }
                yield new MappedItem("message", "assistant",
                        MAPPER.writeValueAsString(Map.of("text", text == null ? "" : text)));
            }
            case "tool_call" -> {
                Object args = Map.of();
                if (payload instanceof Map<?, ?> m && m.get("arguments") != null) {
                    args = m.get("arguments");
                }
                yield new MappedItem("tool_call", "assistant",
                        MAPPER.writeValueAsString(Map.of(
                                "callId", strOr(payload, "callId", "call_unknown"),
                                "name", text == null ? "" : text,
                                "arguments", args
                        )));
            }
            case "tool_result" -> new MappedItem("tool_result", "tool",
                    MAPPER.writeValueAsString(Map.of(
                            "callId", strOr(payload, "callId", "call_unknown"),
                            "name", strOr(payload, "name", ""),
                            "ok", true,
                            "output", text == null ? "" : text,
                            "truncated", false
                    )));
            case "error" -> new MappedItem("error", "system",
                    MAPPER.writeValueAsString(Map.of(
                            "code", "LEGACY_ERROR",
                            "message", text == null ? "" : text
                    )));
            default -> null;
        };
    }

    private static String turnIdOf(Map<String, Object> part, String fallback) {
        Object payload = part.get("payload");
        if (payload instanceof Map<?, ?> m && m.get("turnId") != null) {
            return String.valueOf(m.get("turnId"));
        }
        return fallback;
    }

    private static String roleFromPayload(Object payload, String def) {
        if (payload instanceof Map<?, ?> m && m.get("role") != null) {
            return String.valueOf(m.get("role"));
        }
        return def;
    }

    private static String strOr(Object payload, String key, String def) {
        if (payload instanceof Map<?, ?> m && m.get(key) != null) {
            return String.valueOf(m.get(key));
        }
        return def;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private record MappedItem(String type, String role, String contentJson) {}
}
