package com.tioka.server.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundation.starter.client.authz.AuthorizationSnapshotClient;
import com.foundation.starter.core.config.FoundationProperties;
import com.tioka.framework.foundation.FoundationModeConfiguration;
import com.tioka.framework.security.AuthorizationSnapshotSource;
import com.tioka.framework.security.FoundationPermissionEvaluator;
import com.tioka.framework.security.PermissionEvaluator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** F4 接线：接入模式不再因缺少数据源而拒绝启动，本地模式则完全不引入中台 Bean。 */
class FoundationAuthzConfigurationTest {

    private static final AuthorizationSnapshotClient CLIENT = personId -> Set.of("agent:read");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FoundationAuthzConfiguration.class);

    @Test
    void localModeRegistersNoSnapshotSource() {
        runner.withPropertyValues("tioka.foundation.mode=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AuthorizationSnapshotSource.class);
                });
    }

    @Test
    void foundationModeFailsFastWhenStarterClientIsNotActive() {
        runner.withPropertyValues("tioka.foundation.mode=foundation")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("AuthorizationSnapshotClient");
                });
    }

    @Test
    void foundationModeRegistersAdapter() {
        runner.withPropertyValues("tioka.foundation.mode=foundation")
                .withBean(AuthorizationSnapshotClient.class, () -> CLIENT)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthorizationSnapshotSource.class);
                });
    }

    @Test
    void foundationModeStartsWithRealEvaluatorInsteadOfRejectingStartup() {
        FoundationProperties foundation = new FoundationProperties();
        foundation.setAppCode("tioka");
        foundation.setBaseUrl("https://foundation.example.com");

        new ApplicationContextRunner()
                .withUserConfiguration(FoundationModeConfiguration.class, FoundationAuthzConfiguration.class)
                .withPropertyValues("tioka.foundation.mode=foundation")
                .withBean(FoundationProperties.class, () -> foundation)
                .withBean(AuthorizationSnapshotClient.class, () -> CLIENT)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AuthorizationSnapshotSource.class);
                    assertThat(context).hasSingleBean(PermissionEvaluator.class);
                    assertThat(context.getBean(PermissionEvaluator.class))
                            .isInstanceOf(FoundationPermissionEvaluator.class);
                });
    }
}
