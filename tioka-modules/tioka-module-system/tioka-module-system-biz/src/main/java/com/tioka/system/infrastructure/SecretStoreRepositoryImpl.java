package com.tioka.system.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.system.infrastructure.mapper.SecretStoreMapper;
import com.tioka.system.infrastructure.persistence.SecretStoreDO;
import org.springframework.stereotype.Repository;

@Repository
public class SecretStoreRepositoryImpl implements SecretStoreRepository {
    private final SecretStoreMapper mapper;

    public SecretStoreRepositoryImpl(SecretStoreMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void upsert(SecretStoreDO row) {
        SecretStoreDO existing = mapper.selectOne(Wrappers.<SecretStoreDO>lambdaQuery()
                .eq(SecretStoreDO::getNs, row.getNs())
                .eq(SecretStoreDO::getSecretKey, row.getSecretKey()));
        if (existing == null) {
            mapper.insert(row);
        } else {
            mapper.update(row, Wrappers.<SecretStoreDO>lambdaUpdate()
                    .eq(SecretStoreDO::getNs, row.getNs())
                    .eq(SecretStoreDO::getSecretKey, row.getSecretKey()));
        }
    }

    @Override
    public SecretStoreDO find(String ns, String key) {
        return mapper.selectOne(Wrappers.<SecretStoreDO>lambdaQuery()
                .eq(SecretStoreDO::getNs, ns)
                .eq(SecretStoreDO::getSecretKey, key));
    }

    @Override
    public void softDelete(String ns, String key) {
        mapper.delete(Wrappers.<SecretStoreDO>lambdaQuery()
                .eq(SecretStoreDO::getNs, ns)
                .eq(SecretStoreDO::getSecretKey, key));
    }
}
