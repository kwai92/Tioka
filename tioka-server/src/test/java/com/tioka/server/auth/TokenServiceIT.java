package com.tioka.server.auth;

import com.tioka.framework.error.BizException;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.auth.TokenService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class TokenServiceIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.auth.access-ttl", () -> "1s");
    }

    @Autowired
    TokenService tokens;

    @Test
    void expiredAccessRejected() throws InterruptedException {
        TokenService.TokenPair pair = tokens.issue(42L);
        assertTrue(tokens.verifyAccess(pair.accessToken()).isPresent());
        Thread.sleep(2100);
        assertTrue(tokens.verifyAccess(pair.accessToken()).isEmpty());
    }

    @Test
    void refreshRotates() {
        TokenService.TokenPair first = tokens.issue(43L);
        TokenService.TokenPair second = tokens.refresh(first.refreshToken());
        assertNotEquals(first.accessToken(), second.accessToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());
        assertTrue(tokens.verifyAccess(first.accessToken()).isEmpty());
        assertTrue(tokens.verifyAccess(second.accessToken()).isPresent());
        // 旧 rt 键已删；此处不二次 refresh（重放族吊销见 refreshReuseRevokesFamily）
    }

    @Test
    void refreshReuseRevokesFamily() {
        TokenService.TokenPair first = tokens.issue(44L);
        TokenService.TokenPair second = tokens.refresh(first.refreshToken());
        assertThrows(BizException.class, () -> tokens.refresh(first.refreshToken()));
        assertTrue(tokens.verifyAccess(second.accessToken()).isEmpty());
    }
}
