package com.tioka.system.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.system.infrastructure.mapper.AuthUserMapper;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.springframework.stereotype.Repository;

@Repository
public class AuthUserRepositoryImpl implements AuthUserRepository {
    private final AuthUserMapper mapper;

    public AuthUserRepositoryImpl(AuthUserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AuthUserDO findByUsername(String username) {
        return mapper.selectOne(Wrappers.<AuthUserDO>lambdaQuery().eq(AuthUserDO::getUsername, username));
    }

    @Override
    public AuthUserDO findById(Long id) {
        return mapper.selectById(id);
    }

    @Override
    public void insert(AuthUserDO user) {
        mapper.insert(user);
    }

    @Override
    public long nextId() {
        Long max = mapper.selectMaxId();
        return (max == null ? 0L : max) + 1L;
    }
}
