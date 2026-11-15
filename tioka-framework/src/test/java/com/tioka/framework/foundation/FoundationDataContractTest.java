package com.tioka.framework.foundation;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.foundation.starter.data.AppendOnlyDO;
import com.foundation.starter.data.BaseDO;
import com.foundation.starter.data.ImmutableSnapshotDO;
import com.foundation.starter.data.MutableRecordDO;
import com.foundation.starter.data.config.FoundationMigrationAutoConfiguration;
import com.tioka.framework.persistence.MybatisPlusConfiguration;
import com.tioka.framework.persistence.ScopeLineHandler;
import com.tioka.framework.persistence.TiokaMetaObjectHandler;
import com.tioka.framework.persistence.WorkspaceAwareDO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-02 BAS-26 第 2 步（data）契约守护。
 *
 * <p>Tioka 只合流 4 个通用 DO 基类；空间过滤、审计填充与拦截器仍属 Tioka 特有部分，
 * 不得随 starter 自动装配被替换。本机无 Docker，全部断言在纯 JVM / 纯配置上下文中完成。
 */
class FoundationDataContractTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FoundationMigrationAutoConfiguration.class);

    @Test
    void fourDoBaseClassesComeFromFoundationStarter() {
        assertEquals("com.foundation.starter.data.BaseDO", BaseDO.class.getName());
        assertEquals("com.foundation.starter.data.AppendOnlyDO", AppendOnlyDO.class.getName());
        assertEquals("com.foundation.starter.data.ImmutableSnapshotDO", ImmutableSnapshotDO.class.getName());
        assertEquals("com.foundation.starter.data.MutableRecordDO", MutableRecordDO.class.getName());
        assertTrue(Serializable.class.isAssignableFrom(BaseDO.class));
    }

    @Test
    void tiokaWorkspaceAwareDoExtendsFoundationBaseDo() {
        assertEquals(BaseDO.class, WorkspaceAwareDO.class.getSuperclass());
    }

    @Test
    void tiokaSpecificPersistencePartsRemainLocal() {
        assertEquals("com.tioka.framework.persistence.ScopeLineHandler", ScopeLineHandler.class.getName());
        assertEquals("com.tioka.framework.persistence.TiokaMetaObjectHandler", TiokaMetaObjectHandler.class.getName());
        assertEquals("com.tioka.framework.persistence.MybatisPlusConfiguration", MybatisPlusConfiguration.class.getName());
        assertEquals("com.tioka.framework.persistence.WorkspaceAwareDO", WorkspaceAwareDO.class.getName());
    }

    @Test
    void foundationLogicalDeleteValueMatchesTiokaGlobalConfig() throws Exception {
        TableLogic logic = BaseDO.class.getDeclaredField("deleted").getAnnotation(TableLogic.class);
        assertNotNull(logic, "BaseDO.deleted 必须保留 @TableLogic");
        // Foundation 侧显式化，Tioka application.yml 全局配置同为 false/true，二者必须一致
        assertEquals("false", logic.value());
        assertEquals("true", logic.delval());

        String yml = serverApplicationYml();
        assertTrue(yml.contains("logic-delete-value: true"), "Tioka 全局逻辑删配置须与 @TableLogic 一致");
        assertTrue(yml.contains("logic-not-delete-value: false"), "Tioka 全局逻辑删配置须与 @TableLogic 一致");
    }

    @Test
    void capabilitySwitchGatesStarterAutoConfiguration() {
        runner.run(context -> assertTrue(context.containsBean("flywayNaming"),
                "starter 缺省行为：capability 未显式关闭时应装配"));
        runner.withPropertyValues("foundation.data.enabled=false")
                .run(context -> assertFalse(context.containsBean("flywayNaming"),
                        "关闭 data 能力后，starter 自动配置不得激活"));
        runner.withPropertyValues("foundation.enabled=false", "foundation.data.enabled=true")
                .run(context -> assertFalse(context.containsBean("flywayNaming"),
                        "总开关关闭时，单能力显式开启也不得激活"));
    }

    @Test
    void applicationYmlExplicitlyDisablesDataCapability() throws Exception {
        String yml = serverApplicationYml();
        int foundation = yml.indexOf("\nfoundation:");
        assertTrue(foundation >= 0, "application.yml 应含 foundation 配置段");
        // 精确断言：foundation 段下的 data.enabled=false（避免被其它同名片段误判）
        String section = yml.substring(foundation, Math.min(yml.length(), foundation + 800));
        assertTrue(section.matches("(?s).*\\n\\s+data:\\s*\\n\\s+enabled:\\s*false\\b.*"),
                "Tioka 必须在 foundation 段显式关闭 data 能力，避免仅加依赖就自动装配出冲突 bean");
    }

    private static String serverApplicationYml() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 5; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("tioka-server/src/main/resources/application.yml");
            if (Files.isRegularFile(candidate)) {
                // 仓库 yml 为 CRLF，统一为 LF 便于正则与字面量断言
                return Files.readString(candidate).replace("\r\n", "\n");
            }
            if (dir.getParent() == null) {
                break;
            }
        }
        throw new IllegalStateException("未找到 tioka-server/src/main/resources/application.yml");
    }
}
