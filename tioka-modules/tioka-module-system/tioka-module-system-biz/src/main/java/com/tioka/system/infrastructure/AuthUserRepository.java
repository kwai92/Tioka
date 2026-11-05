package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.persistence.AuthUserDO;

public interface AuthUserRepository {
    AuthUserDO findByUsername(String username);
    AuthUserDO findById(Long id);
    void insert(AuthUserDO user);
    long nextId();
}
