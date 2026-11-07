package db.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * CHT-03：DROP {@code chat_message} 前校验每个会话的 {@code runtime_item} 数 ≥ 原消息数。
 * 表名可注入以便 IT 使用影子表。
 */
public final class ChatMessageDropGuard {
    private static final Logger log = LoggerFactory.getLogger(ChatMessageDropGuard.class);

    public record Tables(String conversation, String message, String item) {
        public static Tables defaults() {
            return new Tables("chat_conversation", "chat_message", "runtime_item");
        }
    }

    private ChatMessageDropGuard() {}

    /**
     * @return 校验失败的会话 ID；空列表表示可通过
     */
    public static List<String> findUndersyncedConversations(Connection c, Tables t) throws Exception {
        if (!tableExists(c, t.message())) {
            return List.of();
        }
        String sql = """
                SELECT c.id
                  FROM %s c
                 WHERE (SELECT COUNT(*) FROM %s m WHERE m.conversation_id = c.id)
                     > (SELECT COUNT(*) FROM %s i WHERE i.session_id = c.runtime_session_id)
                 ORDER BY c.id
                """.formatted(t.conversation(), t.message(), t.item());
        List<String> bad = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                bad.add(rs.getString(1));
            }
        }
        return bad;
    }

    /** 校验通过后 DROP；表已不存在则跳过。失败时抛出并带上会话 ID。 */
    public static void validateAndDrop(Connection c, Tables t) throws Exception {
        if (!tableExists(c, t.message())) {
            log.info("ChatMessageDropGuard: {} 已不存在，跳过", t.message());
            return;
        }
        List<String> bad = findUndersyncedConversations(c, t);
        if (!bad.isEmpty()) {
            String ids = String.join(", ", bad);
            log.error("ChatMessageDropGuard: runtime_item 少于 chat_message，会话: {}", ids);
            throw new IllegalStateException(
                    "CHT-03 迁移失败：以下会话 runtime_item 数 < chat_message 数: " + ids);
        }
        try (Statement st = c.createStatement()) {
            st.execute("DROP TABLE " + t.message());
        }
        log.info("ChatMessageDropGuard: 已 DROP {}", t.message());
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
}
