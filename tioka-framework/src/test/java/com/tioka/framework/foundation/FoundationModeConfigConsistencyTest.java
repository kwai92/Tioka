package com.tioka.framework.foundation;

import com.foundation.starter.core.runtime.FoundationRuntimeMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-02 BAS-26 / ADP-08：Tioka 侧 {@code tioka.foundation.mode} 与底座 starter 侧
 * {@code foundation.client.mode} 必须由同一环境变量派生，且两者对同一取值给出一致语义。
 *
 * <p>底座 starter 未显式配置时按 FOUNDATION（连中台）判定，而 Tioka 缺省是 local（不连中台）。
 * 若两键不同源，就会出现「应用以为单飞、starter 仍连中台」的相反语义。
 */
class FoundationModeConfigConsistencyTest {

    private static final String SHARED_SOURCE = "${TIOKA_FOUNDATION_MODE:local}";

    @Test
    void twoModeKeysAreDerivedFromTheSameEnvironmentVariable() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new FileSystemResource(serverYaml()));
        assertThat(sources).isNotEmpty();
        PropertySource<?> yaml = sources.get(0);

        assertThat(yaml.getProperty("tioka.foundation.mode")).isEqualTo(SHARED_SOURCE);
        assertThat(yaml.getProperty("foundation.client.mode")).isEqualTo(SHARED_SOURCE);
    }

    @Test
    void bothParsersAgreeOnSharedModeValues() {
        for (String raw : new String[]{"local", "foundation", "LOCAL", " Foundation "}) {
            assertThat(FoundationMode.parse(raw).name())
                    .as("两侧对 %s 的判定必须一致", raw)
                    .isEqualTo(FoundationRuntimeMode.parse(raw).name());
        }
        assertThat(FoundationMode.parse(null)).isEqualTo(FoundationMode.LOCAL);
    }

    @Test
    void selfManagedIsAnExplicitBoundaryOnTiokaSide() {
        // 底座已把 self-managed 列为一等公民，Tioka 侧尚未接线；注入该值必须显式失败，
        // 不能静默按 local 运行，否则两侧语义会分叉。
        assertThatThrownBy(() -> FoundationMode.parse("self-managed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FoundationRuntimeMode.parse("self-managed"))
                .isEqualTo(FoundationRuntimeMode.SELF_MANAGED);
    }

    @Test
    void blankModeValueFailsClosedOnStarterSide() {
        // 环境变量注入成空串时，底座不能静默回落成「连中台」
        assertThatThrownBy(() -> FoundationRuntimeMode.parse("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("foundation.client.mode");
    }

    private static Path serverYaml() {
        Path fromModule = Path.of("../tioka-server/src/main/resources/application.yml");
        return Files.exists(fromModule)
                ? fromModule
                : Path.of("tioka-server/src/main/resources/application.yml");
    }
}
