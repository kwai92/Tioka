package com.tioka.system.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.system.infrastructure.mapper.AuthUserMapper;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.springframework.stereotype.Repository;

import java.util.List;

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

    @Override
    public List<AuthUserDO> search(String q, int offset, int limit) {
        return mapper.selectList(searchWrapper(q)
                .orderByDesc(AuthUserDO::getId)
                .last("LIMIT " + Math.max(limit, 1) + " OFFSET " + Math.max(offset, 0)));
    }

    @Override
    public long countSearch(String q) {
        Long n = mapper.selectCount(searchWrapper(q));
        return n == null ? 0L : n;
    }

    private static LambdaQueryWrapper<AuthUserDO> searchWrapper(String q) {
        LambdaQueryWrapper<AuthUserDO> w = Wrappers.lambdaQuery();
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            w.and(x -> x.like(AuthUserDO::getUsername, like).or().like(AuthUserDO::getDisplayName, like));
        }
        return w;
    }
}
