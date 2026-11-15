package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.persistence.AuthUserDO;

import java.time.Instant;
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

    /** OBS-12：时间线重建只读端口——按 {@code update_at} 时间段读回用户（升序）。 */
    List<AuthUserDO> listByRange(Instant from, Instant to);
}
