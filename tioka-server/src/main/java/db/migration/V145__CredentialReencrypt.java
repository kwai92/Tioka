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
 * FND-08：secret_store ECB → CredentialCipher（GCM）重加密。
 *
 * <p>主密钥：遗留 ECB 用 {@code TIOKA_SECRET_MASTER_KEY} / {@code tioka.secret.master-key}；
 * 新密文用 {@code TIOKA_CREDENTIAL_MASTER_KEY} / {@code tioka.credential.master-key}（缺省开发密钥）。
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

        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ns, secret_key, cipher_text FROM secret_store WHERE deleted = false");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }

        int written = 0;
        try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE secret_store SET cipher_text_gcm = ? WHERE ns = ? AND secret_key = ?")) {
            for (Row row : rows) {
                String plain;
                if (LegacyAesEcb.looksLikeLegacy(row.cipherText)) {
                    plain = LegacyAesEcb.decrypt(row.cipherText, legacyKey);
                } else {
                    // 已是 GCM：用当前 cipher 解再写，保证格式与密钥版本一致
                    plain = cipher.decrypt(row.cipherText, row.secretKey, row.ns);
                }
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
            upd.executeBatch();
        }

        if (written != rows.size()) {
            throw new IllegalStateException(
                    "重加密条数不一致：expected=" + rows.size() + " written=" + written);
        }

        try (PreparedStatement check = conn.prepareStatement(
                "SELECT COUNT(*) FROM secret_store WHERE deleted = false AND (cipher_text_gcm IS NULL OR cipher_text_gcm = '')");
             ResultSet rs = check.executeQuery()) {
            rs.next();
            if (rs.getInt(1) != 0) {
                throw new IllegalStateException("仍有行未写入 cipher_text_gcm");
            }
        }

        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE secret_store SET cipher_text = cipher_text_gcm WHERE deleted = false");
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
