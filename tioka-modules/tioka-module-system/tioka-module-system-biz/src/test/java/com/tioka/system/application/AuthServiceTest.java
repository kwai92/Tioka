package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.system.infrastructure.AuthTokenRepository;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    private AuthService svc;

    @BeforeEach
    void setUp() {
        Map<Long, AuthUserDO> byId = new ConcurrentHashMap<>();
        Map<String, AuthUserDO> byName = new ConcurrentHashMap<>();
        AtomicLong seq = new AtomicLong(0);
        AuthUserRepository users = new AuthUserRepository() {
            @Override
            public AuthUserDO findByUsername(String username) {
                return byName.get(username);
            }

            @Override
            public AuthUserDO findById(Long id) {
                return byId.get(id);
            }

            @Override
            public void insert(AuthUserDO user) {
                byId.put(user.getId(), user);
                byName.put(user.getUsername(), user);
                seq.updateAndGet(v -> Math.max(v, user.getId()));
            }

            @Override
            public long nextId() {
                return seq.incrementAndGet();
            }
        };
        Map<String, Long> tokens = new ConcurrentHashMap<>();
        AuthTokenRepository tokenRepo = new AuthTokenRepository() {
            @Override
            public void save(String token, Long userId) {
                tokens.put(token, userId);
            }

            @Override
            public Long findUserId(String token) {
                return tokens.get(token);
            }
        };
        svc = new AuthService(users, tokenRepo);
    }

    @Test
    void loginSuccess() {
        AuthService.LoginResult r = svc.login("admin", "admin123");
        assertNotNull(r.token());
        assertEquals(1L, r.userId());
    }

    @Test
    void loginFail() {
        assertThrows(BizException.class, () -> svc.login("admin", "bad"));
    }
}
