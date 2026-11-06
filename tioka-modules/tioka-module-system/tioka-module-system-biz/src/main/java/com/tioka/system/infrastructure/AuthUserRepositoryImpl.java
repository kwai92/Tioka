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
    public AuthUserDO findByIdpAndSubject(String idp, String externalSubject) {
        return mapper.selectOne(Wrappers.<AuthUserDO>lambdaQuery()
                .eq(AuthUserDO::getIdp, idp)
                .eq(AuthUserDO::getExternalSubject, externalSubject));
    }

    @Override
    public void insert(AuthUserDO user) {
        mapper.insert(user);
    }

    @Override
    public void update(AuthUserDO user) {
        mapper.updateById(user);
    }

    @Override
    public long nextId() {
        return mapper.nextId();
    }
}
