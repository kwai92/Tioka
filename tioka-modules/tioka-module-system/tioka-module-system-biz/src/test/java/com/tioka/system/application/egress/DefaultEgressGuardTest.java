package com.tioka.system.application.egress;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.config.EgressProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultEgressGuardTest {
    private DefaultEgressGuard allowList;
    private DefaultEgressGuard strict;

    @BeforeEach
    void setUp() {
        EgressProperties allow = new EgressProperties();
        allow.setMode(EgressProperties.Mode.ALLOW_LIST);
        allow.setAllowHosts(List.of("localhost", "127.0.0.1", "*.feishu.cn"));
        allowList = new DefaultEgressGuard(allow, false);

        EgressProperties st = new EgressProperties();
        st.setMode(EgressProperties.Mode.STRICT);
        st.setAllowHosts(List.of("169.254.169.254", "localhost"));
        st.setAllowPrivate(true);
        st.setDenyCidrs(List.of("169.254.0.0/16", "100.100.100.200/32"));
        strict = new DefaultEgressGuard(st, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.com/?localhost",
            "https://localhost.evil.com",
            "https://evil.com#@localhost",
            "https://feishu.cn.evil.com",
            "file:///etc/passwd",
            "gopher://example.com"
    })
    void allowListRejects(String url) {
        BizException ex = assertThrows(BizException.class, () -> allowList.assertAllowed(url));
        assertEquals(ErrorCode.EGRESS_DENIED, ex.getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({
            "https://user@localhost:8080/x",
            "https://api.feishu.cn/x",
            "HTTPS://LOCALHOST"
    })
    void allowListAccepts(String url) {
        assertDoesNotThrow(() -> allowList.assertAllowed(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/"})
    void strictRejectsMetadata(String url) {
        BizException ex = assertThrows(BizException.class, () -> strict.assertAllowed(url));
        assertEquals(ErrorCode.EGRESS_DENIED, ex.getErrorCode());
    }
}
