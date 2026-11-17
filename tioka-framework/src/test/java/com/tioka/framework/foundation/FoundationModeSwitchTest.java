package com.tioka.framework.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundation.starter.core.config.CoreAutoConfiguration;
import com.foundation.starter.core.config.FoundationProperties;
import com.tioka.framework.security.AuthorizationSnapshotSource;
import com.tioka.framework.security.FoundationPermissionEvaluator;
import com.tioka.framework.security.LocalPermissionEvaluator;
import com.tioka.framework.security.PermissionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** F-02 BAS-26：双模式开关与「Tioka 已真实依赖 Foundation 底座」的启动期守护。 */
class FoundationModeSwitchTest {

    private static final AuthorizationSnapshotSource SNAPSHOT_SOURCE = personId -> Set.of();

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(FoundationModeConfiguration.class);

    @Test
    void defaultsToLocalModeWithoutGuard() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TiokaFoundationProperties.class);
            assertThat(context.getBean(TiokaFoundationProperties.class).getMode())
                    .isEqualTo(FoundationMode.LOCAL);
            assertThat(context).doesNotHaveBean(FoundationModeGuard.class);
            assertThat(context).hasSingleBean(PermissionEvaluator.class);
            assertThat(context.getBean(PermissionEvaluator.class))
                    .isInstanceOf(LocalPermissionEvaluator.class);
        });
    }

    @Test
    void localModeDoesNotRequireFoundationService() {
        runner.withPropertyValues("tioka.foundation.mode=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FoundationModeGuard.class);
                });
    }

    @Test
    void foundationModeFailsFastWhenStarterContractIsMissing() {
        runner.withPropertyValues("tioka.foundation.mode=foundation")
                .withBean(AuthorizationSnapshotSource.class, () -> SNAPSHOT_SOURCE)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("foundation-starter-core is not active");
                });
    }

    @Test
    void foundationModeRegistersGuardWhenContractIsConfigured() {
        FoundationProperties foundation = new FoundationProperties();
        foundation.setAppCode("tioka");
        foundation.setBaseUrl("https://foundation.example.com");

        runner.withPropertyValues("tioka.foundation.mode=foundation")
                .withBean(FoundationProperties.class, () -> foundation)
                .withBean(AuthorizationSnapshotSource.class, () -> SNAPSHOT_SOURCE)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FoundationModeGuard.class);
                    assertThat(context.getBean(TiokaFoundationProperties.class).isFoundationMode())
                            .isTrue();
                    assertThat(context).hasSingleBean(PermissionEvaluator.class);
                    assertThat(context.getBean(PermissionEvaluator.class))
                            .isInstanceOf(FoundationPermissionEvaluator.class);
                });
    }

    @Test
    void foundationModeFailsFastWithoutSnapshotSourceAdapter() {
        FoundationProperties foundation = new FoundationProperties();
        foundation.setAppCode("tioka");
        foundation.setBaseUrl("https://foundation.example.com");

        runner.withPropertyValues("tioka.foundation.mode=foundation")
                .withBean(FoundationProperties.class, () -> foundation)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("AuthorizationSnapshotSource");
                });
    }

    @Test
    void starterCoreAutoConfigurationCoexistsWithLocalMode() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CoreAutoConfiguration.class))
                .withUserConfiguration(FoundationModeConfiguration.class)
                .withPropertyValues("tioka.foundation.mode=local", "foundation.app-code=tioka")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(FoundationProperties.class);
                    assertThat(context).hasSingleBean(TiokaFoundationProperties.class);
                    assertThat(context).doesNotHaveBean(FoundationModeGuard.class);
                });
    }

    @Test
    void parsesModeValuesLooselyAndRejectsUnknown() {
        assertThat(FoundationMode.parse(null)).isEqualTo(FoundationMode.LOCAL);
        assertThat(FoundationMode.parse("  ")).isEqualTo(FoundationMode.LOCAL);
        assertThat(FoundationMode.parse("LOCAL")).isEqualTo(FoundationMode.LOCAL);
        assertThat(FoundationMode.parse(" Foundation ")).isEqualTo(FoundationMode.FOUNDATION);
        assertThatThrownBy(() -> FoundationMode.parse("hybrid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local|foundation");
    }

    @Test
    void guardRejectsDefaultLocalhostBaseUrl() {
        TiokaFoundationProperties properties = new TiokaFoundationProperties();
        properties.setMode(FoundationMode.FOUNDATION);
        FoundationProperties foundation = new FoundationProperties();
        foundation.setAppCode("tioka");
        foundation.setBaseUrl(FoundationModeGuard.DEFAULT_BASE_URL);

        FoundationModeGuard guard = new FoundationModeGuard(properties, providerOf(foundation));
        assertThatThrownBy(guard::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("foundation.base-url");
    }

    @Test
    void guardSkipsValidationInLocalMode() {
        TiokaFoundationProperties properties = new TiokaFoundationProperties();
        FoundationModeGuard guard = new FoundationModeGuard(properties, providerOf(null));
        assertThat(properties.isFoundationMode()).isFalse();
        guard.validate();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<FoundationProperties> providerOf(FoundationProperties value) {
        ObjectProvider<FoundationProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
