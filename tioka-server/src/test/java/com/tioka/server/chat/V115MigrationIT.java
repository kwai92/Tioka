package com.tioka.server.chat;

import com.tioka.server.support.IntegrationTestSupport;
import db.migration.ChatMessageDropGuard;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHT-03：V126 校验 + DROP chat_message（验收名沿用 M1-00 {@code V115MigrationIT}）。
 */
@SpringBootTest
@Tag("it")
class V115MigrationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void chatMessageDroppedAfterMigrate() {
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                (rs, i) -> rs.getString(1));
        assertTrue(versions.contains("115"), "missing V115: " + versions);
        assertTrue(versions.contains("126"), "missing V126: " + versions);

        Set<String> tables = jdbc.query(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                        (rs, i) -> rs.getString(1))
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        assertTrue(!tables.contains("chat_message"), "V126 后不应再有 chat_message");
        assertTrue(tables.contains("chat_conversation"), "chat_conversation 应保留");
        assertTrue(tables.contains("runtime_item"), "runtime_item 应保留");
    }

    @Test
    void validateFailsWhenItemsFewerThanMessages() throws Exception {
        dropShadow();
        createShadow();
        String conv = "conv_" + shortId();
        String thread = "th_" + shortId();
        jdbc.update("INSERT INTO v126_chat_conversation(id, runtime_session_id) VALUES (?,?)",
                conv, thread);
        jdbc.update("INSERT INTO v126_chat_message(id, conversation_id) VALUES (?,?)",
                "msg_" + shortId(), conv);
        jdbc.update("INSERT INTO v126_chat_message(id, conversation_id) VALUES (?,?)",
                "msg_" + shortId(), conv);
        // 仅 1 条 item < 2 条 message
        jdbc.update("INSERT INTO v126_runtime_item(session_id, seq) VALUES (?,1)", thread);

        List<String> bad;
        try (var conn = jdbc.getDataSource().getConnection()) {
            bad = ChatMessageDropGuard.findUndersyncedConversations(conn, shadowTables());
        }
        assertEquals(List.of(conv), bad);

        try (var conn = jdbc.getDataSource().getConnection()) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> ChatMessageDropGuard.validateAndDrop(conn, shadowTables()));
            assertTrue(ex.getMessage().contains(conv));
        }
        // 失败后表仍在
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='v126_chat_message'",
                Integer.class));
    }

    @Test
    void validateAndDropWhenSynced() throws Exception {
        dropShadow();
        createShadow();
        String conv = "conv_" + shortId();
        String thread = "th_" + shortId();
        jdbc.update("INSERT INTO v126_chat_conversation(id, runtime_session_id) VALUES (?,?)",
                conv, thread);
        jdbc.update("INSERT INTO v126_chat_message(id, conversation_id) VALUES (?,?)",
                "msg_" + shortId(), conv);
        jdbc.update("INSERT INTO v126_runtime_item(session_id, seq) VALUES (?,1)", thread);
        jdbc.update("INSERT INTO v126_runtime_item(session_id, seq) VALUES (?,2)", thread);

        try (var conn = jdbc.getDataSource().getConnection()) {
            ChatMessageDropGuard.validateAndDrop(conn, shadowTables());
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='v126_chat_message'",
                Integer.class));
    }

    private static ChatMessageDropGuard.Tables shadowTables() {
        return new ChatMessageDropGuard.Tables(
                "v126_chat_conversation", "v126_chat_message", "v126_runtime_item");
    }

    private void dropShadow() {
        jdbc.execute("DROP TABLE IF EXISTS v126_chat_message");
        jdbc.execute("DROP TABLE IF EXISTS v126_runtime_item");
        jdbc.execute("DROP TABLE IF EXISTS v126_chat_conversation");
    }

    private void createShadow() {
        jdbc.execute("""
                CREATE TABLE v126_chat_conversation (
                  id VARCHAR(64) PRIMARY KEY,
                  runtime_session_id VARCHAR(64) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE v126_chat_message (
                  id VARCHAR(64) PRIMARY KEY,
                  conversation_id VARCHAR(64) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE v126_runtime_item (
                  session_id VARCHAR(64) NOT NULL,
                  seq BIGINT NOT NULL,
                  PRIMARY KEY (session_id, seq)
                )
                """);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
