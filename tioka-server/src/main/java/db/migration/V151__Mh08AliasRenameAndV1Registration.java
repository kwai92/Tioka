package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.versioning.ModelHubSpecs;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MH-08（ADR-041 §9 V151）：model_logical → model_alias 重命名与用途/指向列
 * + 存量对象登记 cfg_version 第 1 版 + model_default_plan → sys.* 系统别名。
 * <p>spec 构造与 sys.* 命名共用 {@link ModelHubSpecs}（model-api，spec 口径单一事实源）；
 * 注册逻辑自包含（server 对 biz 模块仅 runtime 依赖，仿 V104/V145 先例）。
 * 幂等：别名行 ON CONFLICT DO NOTHING；cfg_version 已有该对象版本时仅回填指针。
 */
public class V151__Mh08AliasRenameAndV1Registration extends BaseJavaMigration {

    static final String NOTE = "V151 MH-08 存量对象登记第 1 版（ADR-041 §9）";

    /** 迁移内自带 ObjectMapper（db.migration 不受 ENG-05 注入约束，同 V104 先例）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        if (!tableExists(c, "cfg_version")) {
            return;
        }
        ensureAliasTable(c);
        List<ModelHubSpecs.AliasSeed> seeds = convertDefaultPlans(c);
        registerProviders(c);
        registerBindings(c);
        registerAliases(c);
        registerPlans(c);
        registerSeeds(c, seeds);
    }

    // ---------------------------------------------------------------- rename

    static void ensureAliasTable(Connection c) throws SQLException {
        if (!tableExists(c, "model_alias")) {
            if (!tableExists(c, "model_logical")) {
                throw new IllegalStateException("model_logical / model_alias 均不存在，库结构异常");
            }
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE model_logical RENAME TO model_alias");
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE model_alias ADD COLUMN IF NOT EXISTS purpose VARCHAR(16)");
            st.execute("ALTER TABLE model_alias ADD COLUMN IF NOT EXISTS target_type VARCHAR(16)");
            st.execute("ALTER TABLE model_alias ADD COLUMN IF NOT EXISTS target_id VARCHAR(64)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_model_alias_name ON model_alias (name)");
        }
    }

    // ------------------------------------------------- default_plan → sys.*

    /** 读 model_default_plan 各行并转为 sys.* 别名种子（纯查询；写入在 insertSeed）。 */
    static List<ModelHubSpecs.AliasSeed> readDefaultPlanSeeds(Connection c) throws SQLException {
        List<ModelHubSpecs.AliasSeed> seeds = new ArrayList<>();
        if (!tableExists(c, "model_default_plan")) {
            return seeds;
        }
        List<String[]> rows = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT purpose, routing_plan_id, binding_id FROM model_default_plan")) {
            while (rs.next()) {
                rows.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
            }
        }
        for (String[] row : rows) {
            try {
                seeds.add(ModelHubSpecs.sysAliasSeed(row[0], row[1], row[2]));
            } catch (IllegalStateException e) {
                // 无指向的脏行跳过，不阻断迁移
            }
        }
        return seeds;
    }

    private List<ModelHubSpecs.AliasSeed> convertDefaultPlans(Connection c) throws SQLException {
        List<ModelHubSpecs.AliasSeed> seeds = readDefaultPlanSeeds(c);
        for (ModelHubSpecs.AliasSeed seed : seeds) {
            insertSeedAlias(c, seed);
        }
        return seeds;
    }

    private static void insertSeedAlias(Connection c, ModelHubSpecs.AliasSeed seed) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO model_alias (id, name, purpose, target_type, target_id,
                    create_at, update_at, create_by, update_by, deleted)
                VALUES (?, ?, ?, ?, ?, now(), now(), 'system', 'system', FALSE)
                ON CONFLICT (id) DO NOTHING
                """)) {
            ps.setString(1, seed.id());
            ps.setString(2, seed.name());
            ps.setString(3, seed.purpose());
            ps.setString(4, seed.targetType());
            ps.setString(5, seed.targetId());
            if (ps.executeUpdate() == 1) {
                insertChange(c, ModelHubSpecs.OT_ALIAS, seed.id(), null,
                        "{\"migratedFrom\":\"model_default_plan\",\"purpose\":\"" + seed.purpose()
                                + "\",\"targetType\":\"" + seed.targetType()
                                + "\",\"targetId\":\"" + seed.targetId() + "\"}");
            }
        }
    }

    // ------------------------------------------------------- registrations

    static void registerProviders(Connection c) throws SQLException {
        String sql = "SELECT id, name, base_url, api_key_ref FROM model_provider WHERE deleted = FALSE";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                registerFirstVersion(c, ModelHubSpecs.OT_PROVIDER, rs.getString("id"),
                        ModelHubSpecs.providerSpec(MAPPER, rs.getString("name"), rs.getString("base_url"),
                                rs.getString("api_key_ref")));
            }
        }
    }

    static void registerBindings(Connection c) throws SQLException {
        String sql = """
                SELECT id, logical_model_id, provider_id, model_name, kind, context_window, embedding_dim,
                       input_price_micros, output_price_micros, rpm_limit, tpm_limit
                  FROM model_binding WHERE deleted = FALSE
                """;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                registerFirstVersion(c, ModelHubSpecs.OT_BINDING, rs.getString("id"),
                        ModelHubSpecs.bindingSpec(MAPPER, rs.getString("logical_model_id"), rs.getString("provider_id"),
                                rs.getString("model_name"), rs.getString("kind"),
                                (Integer) rs.getObject("context_window"),
                                (Integer) rs.getObject("embedding_dim"),
                                (Long) rs.getObject("input_price_micros"),
                                (Long) rs.getObject("output_price_micros"),
                                (Integer) rs.getObject("rpm_limit"),
                                (Integer) rs.getObject("tpm_limit")));
            }
        }
    }

    static void registerAliases(Connection c) throws SQLException {
        String sql = """
                SELECT id, name, capability_tags, purpose, target_type, target_id
                  FROM model_alias WHERE deleted = FALSE
                """;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                registerFirstVersion(c, ModelHubSpecs.OT_ALIAS, rs.getString("id"),
                        ModelHubSpecs.aliasSpec(MAPPER, rs.getString("name"), rs.getString("capability_tags"),
                                rs.getString("purpose"), rs.getString("target_type"),
                                rs.getString("target_id")));
            }
        }
    }

    static void registerPlans(Connection c) throws SQLException {
        String sql = """
                SELECT id, name, type, primary_binding_id, fallback_binding_id, hard_binding_id, fallback_chain
                  FROM model_routing_plan WHERE deleted = FALSE
                """;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                registerFirstVersion(c, ModelHubSpecs.OT_ROUTING_PLAN, rs.getString("id"),
                        ModelHubSpecs.planSpec(MAPPER, rs.getString("name"), rs.getString("type"),
                                rs.getString("primary_binding_id"), rs.getString("fallback_binding_id"),
                                rs.getString("hard_binding_id"), rs.getString("fallback_chain")));
            }
        }
    }

    private void registerSeeds(Connection c, List<ModelHubSpecs.AliasSeed> seeds) throws SQLException {
        for (ModelHubSpecs.AliasSeed seed : seeds) {
            registerFirstVersion(c, ModelHubSpecs.OT_ALIAS, seed.id(), seed.spec(MAPPER));
        }
    }

    /**
     * 登记第 1 版并回填指针列；该对象已有任意版本（幂等重跑）时跳过登记、仅回填指针。
     */
    static boolean registerFirstVersion(Connection c, String objectType, String objectId, JsonNode spec)
            throws SQLException {
        if (hasAnyVersion(c, objectType, objectId)) {
            backfillPointer(c, objectType, objectId);
            return false;
        }
        String versionId = "cfgv_" + shortId();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_version (version_id, object_type, object_id, version_no, spec,
                    content_hash, status, publish_note, published_by, published_at)
                VALUES (?, ?, ?, 1, ?::jsonb, ?, 'PUBLISHED', ?, 'system', now())
                """)) {
            ps.setString(1, versionId);
            ps.setString(2, objectType);
            ps.setString(3, objectId);
            ps.setString(4, ModelHubSpecs.canonicalJson(MAPPER, spec));
            ps.setString(5, ModelHubSpecs.contentHash(MAPPER, spec));
            ps.setString(6, NOTE);
            ps.executeUpdate();
        }
        backfillPointer(c, objectType, objectId);
        insertChange(c, objectType, objectId, null,
                "{\"versionNo\":1,\"versionId\":\"" + versionId + "\",\"action\":\"register\"}");
        return true;
    }

    static void backfillPointer(Connection c, String objectType, String objectId) throws SQLException {
        String sql = switch (objectType) {
            case ModelHubSpecs.OT_PROVIDER ->
                    "UPDATE model_provider p SET current_version_id = v.version_id, current_version_no = 1 "
                            + "FROM cfg_version v WHERE v.object_type = 'model_provider' "
                            + "AND v.object_id = p.id AND v.version_no = 1 AND p.id = ?";
            case ModelHubSpecs.OT_BINDING ->
                    "UPDATE model_binding p SET current_version_id = v.version_id, current_version_no = 1 "
                            + "FROM cfg_version v WHERE v.object_type = 'model_binding' "
                            + "AND v.object_id = p.id AND v.version_no = 1 AND p.id = ?";
            case ModelHubSpecs.OT_ALIAS ->
                    "UPDATE model_alias p SET current_version_id = v.version_id, current_version_no = 1 "
                            + "FROM cfg_version v WHERE v.object_type = 'model_alias' "
                            + "AND v.object_id = p.id AND v.version_no = 1 AND p.id = ?";
            case ModelHubSpecs.OT_ROUTING_PLAN ->
                    "UPDATE model_routing_plan p SET current_version_id = v.version_id, current_version_no = 1 "
                            + "FROM cfg_version v WHERE v.object_type = 'model_routing_plan' "
                            + "AND v.object_id = p.id AND v.version_no = 1 AND p.id = ?";
            default -> null;
        };
        if (sql == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, objectId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------- helpers

    private static boolean hasAnyVersion(Connection c, String objectType, String objectId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM cfg_version WHERE object_type = ? AND object_id = ? LIMIT 1")) {
            ps.setString(1, objectType);
            ps.setString(2, objectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void insertChange(Connection c, String objectType, String targetKey,
                                     String beforeJson, String afterJson) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO cfg_change (object_type, target_key, before_json, after_json,
                    actor_id, reason, created_at)
                VALUES (?, ?, ?::jsonb, ?::jsonb, 'system', ?, now())
                """)) {
            ps.setString(1, objectType);
            ps.setString(2, targetKey);
            ps.setString(3, beforeJson);
            ps.setString(4, afterJson);
            ps.setString(5, NOTE);
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

    /** 迁移内生成版本 ID；Flyway 环境不适用业务 ID 规则（同 V104 先例）。 */
    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
