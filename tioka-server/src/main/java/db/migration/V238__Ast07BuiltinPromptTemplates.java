package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.model.api.prompt.BuiltinPromptTemplates;
import com.tioka.model.api.prompt.PromptTemplateSpec;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * AST-07（ADR-041 §4 / §9 V237 / V238）：内置 Prompt 模板（{@code sys.*}）登记第 1 版。
 *
 * <p>与 V151 同构：身份行写入 {@code prm_template}（幂等 ON CONFLICT DO NOTHING），
 * 内容写入 {@code cfg_version}（version_no=1，status=PUBLISHED），并回填当前生效版本指针。
 * spec 构造与规范化 JSON 共用 model-api（{@link BuiltinPromptTemplates} / {@link ModelHubSpecs}），
 * 与运行时发布单一事实源一致。
 *
 * <p>幂等：身份行按固定 ID（{@code prmtpl_sys_<名>}）去重；cfg_version 已有该对象版本时跳过登记、仅回填指针。
 */
public class V238__Ast07BuiltinPromptTemplates extends BaseJavaMigration {

    static final String NOTE = "V238 AST-07 内置 Prompt 模板登记第 1 版（ADR-041 §4）";

    /** 迁移内自带 ObjectMapper（db.migration 不受 ENG-05 注入约束，同 V104 / V151 先例）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        if (!tableExists(c, "prm_template") || !tableExists(c, "cfg_version")) {
            return;
        }
        for (PromptTemplateSpec spec : BuiltinPromptTemplates.list()) {
            String id = BuiltinPromptTemplates.idOf(spec.name());
            insertTemplateRow(c, id, spec);
            if (!hasAnyVersion(c, id)) {
                registerFirstVersion(c, id, spec);
            }
            backfillPointer(c, id);
        }
    }

    // ---------------------------------------------------------------- 写入

    private static void insertTemplateRow(Connection c, String id, PromptTemplateSpec spec) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO prm_template (id, name, scope, scope_ref,
                    create_at, update_at, create_by, update_by, deleted)
                VALUES (?, ?, ?, ?, now(), now(), 'system', 'system', FALSE)
                ON CONFLICT (id) DO NOTHING
                """)) {
            ps.setString(1, id);
            ps.setString(2, spec.name());
            ps.setString(3, spec.scope());
            ps.setString(4, spec.scopeRef());
            ps.executeUpdate();
        }
    }

    private static void registerFirstVersion(Connection c, String objectId, PromptTemplateSpec spec)
            throws SQLException {
        JsonNode json = spec.toJson(MAPPER);
        String versionId = "cfgv_" + shortId();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_version (version_id, object_type, object_id, version_no, spec,
                    content_hash, status, publish_note, published_by, published_at)
                VALUES (?, ?, ?, 1, ?::jsonb, ?, 'PUBLISHED', ?, 'system', now())
                """)) {
            ps.setString(1, versionId);
            ps.setString(2, ModelHubSpecs.OT_PROMPT_TEMPLATE);
            ps.setString(3, objectId);
            ps.setString(4, BuiltinPromptTemplates.canonicalJson(MAPPER, spec));
            ps.setString(5, ModelHubSpecs.contentHash(MAPPER, json));
            ps.setString(6, NOTE);
            ps.executeUpdate();
        }
        insertChange(c, objectId, "{\"versionNo\":1,\"versionId\":\"" + versionId + "\",\"action\":\"register\"}");
    }

    private static void backfillPointer(Connection c, String objectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE prm_template p SET current_version_id = v.version_id, current_version_no = 1
                  FROM cfg_version v
                 WHERE v.object_type = ? AND v.object_id = p.id AND v.version_no = 1 AND p.id = ?
                """)) {
            ps.setString(1, ModelHubSpecs.OT_PROMPT_TEMPLATE);
            ps.setString(2, objectId);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean hasAnyVersion(Connection c, String objectId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM cfg_version WHERE object_type = ? AND object_id = ? LIMIT 1")) {
            ps.setString(1, ModelHubSpecs.OT_PROMPT_TEMPLATE);
            ps.setString(2, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void insertChange(Connection c, String targetKey, String afterJson) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_change (object_type, target_key, before_json, after_json,
                    actor_id, reason, created_at)
                VALUES (?, ?, NULL, ?::jsonb, 'system', ?, now())
                """)) {
            ps.setString(1, ModelHubSpecs.OT_PROMPT_TEMPLATE);
            ps.setString(2, targetKey);
            ps.setString(3, afterJson);
            ps.setString(4, NOTE);
            ps.executeUpdate();
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
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

    /** 迁移内生成版本 ID；Flyway 环境不适用业务 ID 规则（同 V151 先例）。 */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
