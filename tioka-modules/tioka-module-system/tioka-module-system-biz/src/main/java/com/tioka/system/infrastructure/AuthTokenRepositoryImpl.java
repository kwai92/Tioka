package com.tioka.system.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.system.infrastructure.mapper.AuthTokenMapper;
import com.tioka.system.infrastructure.persistence.AuthTokenDO;
import org.springframework.stereotype.Repository;

@Repository
public class AuthTokenRepositoryImpl implements AuthTokenRepository {
    private final AuthTokenMapper mapper;

    public AuthTokenRepositoryImpl(AuthTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String token, Long userId) {
        mapper.delete(Wrappers.<AuthTokenDO>lambdaQuery().eq(AuthTokenDO::getToken, token));
        AuthTokenDO row = new AuthTokenDO();
        row.setToken(token);
        row.setUserId(userId);
        mapper.insert(row);
    }

    @Override
    public Long findUserId(String token) {
        AuthTokenDO row = mapper.selectById(token);
        return row == null ? null : row.getUserId();
    }
}
