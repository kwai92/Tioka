package com.tioka.system.api.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** W0-04：ArchUnit/契约 — SPI 包存在。 */
class FoundationSpiPackageTest {
    @Test
    void spiTypesPresent() {
        // IdPAdapter 已迁入 system-biz OidcProviderRegistry 内部（FND-05）
        assertNotNull(ImChannelAdapter.class);
        assertNotNull(BizSystemAdapter.class);
        assertNotNull(AuditSink.class);
        assertNotNull(SecretStore.class);
        assertNotNull(PrincipalLoaderApi.class);
    }
}
