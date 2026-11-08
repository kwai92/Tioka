package db.migration;

import com.tioka.framework.security.AesGcmCredentialCipher;
import com.tioka.framework.security.CredentialCipher;
import com.tioka.framework.security.LegacyAesEcb;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * FND-08：secret_store 中遗留 AES/ECB 密文 → CredentialCipher（AES-256-GCM）。
 *
 * <p>已是 {@code v{n}:...} 的行跳过（视为已迁移），只转换 ECB 行。
 * 主密钥：遗留 ECB 用 {@code TIOKA_SECRET_MASTER_KEY}；新密文用 {@code TIOKA_CREDENTIAL_MASTER_KEY}。
 */
public class V145__CredentialReencrypt extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        String legacyKey = firstNonBlank(
                System.getenv("TIOKA_SECRET_MASTER_KEY"),
                System.getProperty("tioka.secret.master-key"),
                LegacyAesEcb.DEV_DEFAULT_MASTER_KEY);
        String credKey = firstNonBlank(
                System.getenv("TIOKA_CREDENTIAL_MASTER_KEY"),
                System.getProperty("tioka.credential.master-key"),
                AesGcmCredentialCipher.DEV_DEFAULT_MASTER_KEY);
        int version = parseVersion(firstNonBlank(
                System.getenv("TIOKA_CREDENTIAL_KEY_VERSION"),
                System.getProperty("tioka.credential.key-version"),
                "1"));
        CredentialCipher cipher = new AesGcmCredentialCipher(credKey, version);

        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE secret_store ADD COLUMN IF NOT EXISTS cipher_text_gcm TEXT");
        }

        List<Row> legacy = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ns, secret_key, cipher_text FROM secret_store WHERE deleted = false");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String ct = rs.getString(3);
                if (LegacyAesEcb.looksLikeLegacy(ct)) {
                    legacy.add(new Row(rs.getString(1), rs.getString(2), ct));
                }
            }
        }

        int written = 0;
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE secret_store SET cipher_text_gcm = ? WHERE ns = ? AND secret_key = ?")) {
            for (Row row : legacy) {
                String plain = LegacyAesEcb.decrypt(row.cipherText, legacyKey);
                String gcm = cipher.encrypt(plain, row.secretKey, row.ns);
                String roundTrip = cipher.decrypt(gcm, row.secretKey, row.ns);
                if (!plain.equals(roundTrip)) {
                    throw new IllegalStateException(
                            "GCM 自检失败 ns=" + row.ns + " key=" + row.secretKey);
                }
                upd.setString(1, gcm);
                upd.setString(2, row.ns);
                upd.setString(3, row.secretKey);
                upd.addBatch();
                written++;
            }
            if (written > 0) {
                upd.executeBatch();
            }
        }

        if (written != legacy.size()) {
            throw new IllegalStateException(
                    "重加密条数不一致：expected=" + legacy.size() + " written=" + written);
        }

        try (PreparedStatement check = conn.prepareStatement(
                """
                SELECT COUNT(*) FROM secret_store
                WHERE deleted = false
                  AND cipher_text_gcm IS NOT NULL
                  AND cipher_text_gcm <> ''
                """);
             ResultSet rs = check.executeQuery()) {
            rs.next();
            if (rs.getInt(1) != written) {
                throw new IllegalStateException(
                        "cipher_text_gcm 写入条数不符：expected=" + written + " got=" + rs.getInt(1));
            }
        }

        try (Statement st = conn.createStatement()) {
            // 只覆盖已转换行，勿把已是 GCM 的 cipher_text 置空
            st.execute("""
                    UPDATE secret_store
                    SET cipher_text = cipher_text_gcm
                    WHERE cipher_text_gcm IS NOT NULL AND cipher_text_gcm <> ''
                    """);
            st.execute("ALTER TABLE secret_store DROP COLUMN cipher_text_gcm");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private static int parseVersion(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return 1;
        }
    }

    private record Row(String ns, String secretKey, String cipherText) {}
}
