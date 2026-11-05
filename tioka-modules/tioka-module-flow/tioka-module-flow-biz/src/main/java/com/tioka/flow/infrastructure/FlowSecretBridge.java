package com.tioka.flow.infrastructure;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Flow 侧 SecretStore 唯一入口（ArchUnit 白名单本包）。
 * 引用格式：{@::key
 */
@Component
public class FlowSecretBridge {
    private final ObjectProvider<SecretStore> secretStore;

    public FlowSecretBridge(ObjectProvider<SecretStore> secretStore) {
        this.secretStore = secretStore;
    }

    public String resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "secret ref 为空");
        }
        if (!ref.contains("::")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "secret ref 必须为 namespace::key: " + ref);
        }
        SecretStore store = secretStore.getIfAvailable();
        if (store == null) {
            throw new BizException(ErrorCode.DEPENDENCY, "SecretStore 不可用");
        }
        String[] parts = ref.split("::", 2);
        String value = store.get(parts[0], parts[1]);
        if (value == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "secret 不存在: " + ref);
        }
        return value;
    }

    public String resolveOrNull(String ref) {
        if (ref == null || ref.isBlank() || !ref.contains("::")) return null;
        SecretStore store = secretStore.getIfAvailable();
        if (store == null) return null;
        String[] parts = ref.split("::", 2);
        return store.get(parts[0], parts[1]);
    }
}
