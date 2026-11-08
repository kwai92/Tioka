package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.persistence.AuthUserDO;

import java.util.List;

public interface AuthUserRepository {
    AuthUserDO findByUsername(String username);
    AuthUserDO findById(Long id);
    AuthUserDO findByIdpAndSubject(String idp, String externalSubject);
    void insert(AuthUserDO user);
    void update(AuthUserDO user);
    long nextId();

    /** HCI-03：按 username / displayName 模糊搜索，分页。 */
    List<AuthUserDO> search(String q, int offset, int limit);

    long countSearch(String q);
}
