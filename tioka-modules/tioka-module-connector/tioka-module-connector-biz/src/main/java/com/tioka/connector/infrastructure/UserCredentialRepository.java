package com.tioka.connector.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.connector.infrastructure.mapper.UserCredentialMapper;
import com.tioka.connector.infrastructure.persistence.UserCredentialDO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@code sys_user_credential} 存取封装（FND-08）。application 层经此访问，禁直持 Mapper。
 */
@Repository
public class UserCredentialRepository {

    private final UserCredentialMapper mapper;

    public UserCredentialRepository(UserCredentialMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(UserCredentialDO row) {
        mapper.insert(row);
    }

    public void updateById(UserCredentialDO row) {
        mapper.updateById(row);
    }

    /** 按 (user_id, provider) 唯一键取行（含任意状态：ACTIVE / REVOKED / REFRESH_FAILED）。 */
    public Optional<UserCredentialDO> findByUserAndProvider(String userId, String provider) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<UserCredentialDO>lambdaQuery()
                .eq(UserCredentialDO::getUserId, userId)
                .eq(UserCredentialDO::getProvider, provider)));
    }
}
