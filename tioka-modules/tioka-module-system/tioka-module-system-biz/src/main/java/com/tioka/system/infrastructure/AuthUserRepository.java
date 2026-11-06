package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.persistence.AuthUserDO;

public interface AuthUserRepository {
    AuthUserDO findByUsername(String username);
    AuthUserDO findById(Long id);
    AuthUserDO findByIdpAndSubject(String idp, String externalSubject);
    void insert(AuthUserDO user);
    void update(AuthUserDO user);
    long nextId();
}
