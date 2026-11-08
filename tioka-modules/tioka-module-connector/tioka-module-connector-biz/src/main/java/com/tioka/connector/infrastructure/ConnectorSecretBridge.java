package com.tioka.connector.infrastructure;

import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Connector 侧 SecretStore 读入口（ADR-014 D5：仅 infrastructure 可 get）。
 * 引用格式：namespace::key
 */
@Component
public class ConnectorSecretBridge {

    private final ObjectProvider<SecretStore> secretStore;

    public ConnectorSecretBridge(ObjectProvider<SecretStore> secretStore) {
        this.secretStore = secretStore;
    }

    /** 解析密钥；缺失或格式非法时返回 null。 */
    public String resolveOrNull(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("::")) {
            return null;
        }
        SecretStore store = secretStore.getIfAvailable();
        if (store == null) {
            return null;
        }
        String[] parts = ref.split("::", 2);
        return store.get(parts[0], parts[1]);
    }
}
