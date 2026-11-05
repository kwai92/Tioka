package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.persistence.SecretStoreDO;

public interface SecretStoreRepository {
    void upsert(SecretStoreDO row);
    SecretStoreDO find(String ns, String key);
    void softDelete(String ns, String key);
}
