package com.tioka.system.infrastructure;

public interface AuthTokenRepository {
    void save(String token, Long userId);
    Long findUserId(String token);
}
