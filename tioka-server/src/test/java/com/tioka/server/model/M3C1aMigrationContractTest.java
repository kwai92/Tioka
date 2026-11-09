package com.tioka.server.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 批次 C-1a：MH-08 版本化 / 别名 / 系统别名迁移源码契约守护（离线、纯文件断言，
 * 做法同 {@code M2B4VaultContractTest} 的 nio 读文件）。
 */
class M3C1aMigrationContractTest {

    private static Path repoRoot;

    private static final String V151_JAVA =
            "tioka-server/src/main/java/db/migration/V151__Mh08AliasRenameAndV1Registration.java";
    private static final String V156_SQL =
            "tioka-server/src/main/resources/db/migration/V156__m3_drop_model_default_plan.sql";
    private static final String V222_SQL =
            "tioka-server/src/main/resources/db/migration/V222__m3_mh08_usage_version_columns.sql";
    private static final String V151_CONTRACT = "docs/contracts/db/V151__Mh08AliasRenameAndV1Registration.sql";
    private static final String V156_CONTRACT = "docs/contracts/db/V156__m3_drop_model_default_plan.sql";
    private static final String V222_CONTRACT = "docs/contracts/db/V222__m3_mh08_usage_version_columns.sql";
    private static final String SPECS =
            "tioka-modules/tioka-module-model/tioka-module-model-api/src/main/java"
                    + "/com/tioka/model/api/versioning/ModelHubSpecs.java";

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("V151：重命名 model_logical → model_alias + purpose/target 列")
    void v151RenamesLogicalToAlias() throws IOException {
        String v151 = read(V151_JAVA);
        assertTrue(v151.contains("ALTER TABLE model_logical RENAME TO model_alias"),
                "V151 必须重命名 model_logical → model_alias");
        for (String col : new String[]{"purpose", "target_type", "target_id"}) {
            assertTrue(v151.contains("ADD COLUMN IF NOT EXISTS " + col),
                    "model_alias 缺少契约列：" + col);
        }
        assertFalse(v151.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");
    }

    @Test
    @DisplayName("V151：存量对象登记第 1 版 + 回填指针 + sys.* 系统别名转换")
    void v151RegistersFirstVersionsAndSysAliases() throws IOException {
        String v151 = read(V151_JAVA);
        // 四类对象（objectType 与 CfgStore.updateTypeTablePointer 映射一致，经 model-api 单一事实源常量）
        for (String ot : new String[]{
                "ModelHubSpecs.OT_PROVIDER", "ModelHubSpecs.OT_BINDING",
                "ModelHubSpecs.OT_ALIAS", "ModelHubSpecs.OT_ROUTING_PLAN"}) {
            assertTrue(v151.contains(ot), "V151 未登记对象类型：" + ot);
        }
        assertTrue(v151.contains("INSERT INTO cfg_version"), "必须写 cfg_version 第 1 版");
        assertTrue(v151.contains("current_version_id = v.version_id"), "必须回填投影表指针列");
        assertTrue(v151.contains("INSERT INTO model_alias"), "model_default_plan 行必须转 sys.* 别名");
        assertTrue(v151.contains("model_default_plan"), "必须读取 model_default_plan");
        assertTrue(v151.contains("INSERT INTO cfg_change"), "sys.* 转换必须留变更记录");
        assertTrue(v151.contains("ModelHubSpecs"), "spec 构造必须共用 model-api 单一事实源");
    }

    @Test
    @DisplayName("V156：破坏性删除 model_default_plan")
    void v156DropsDefaultPlan() throws IOException {
        String v156 = read(V156_SQL);
        assertTrue(v156.contains("DROP TABLE IF EXISTS model_default_plan"), "V156 必须删除 model_default_plan");
        assertTrue(v156.contains("V151"), "必须注明前置迁移（sys.* 转换）");
        assertTrue(v156.contains("演练"), "破坏性迁移必须登记演练待办");
        assertFalse(v156.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");
    }

    @Test
    @DisplayName("V222：model_usage 版本列")
    void v222AddsUsageVersionColumns() throws IOException {
        String v222 = read(V222_SQL);
        for (String col : new String[]{"binding_version_no", "alias_version_no", "plan_version_no"}) {
            assertTrue(v222.contains("ADD COLUMN IF NOT EXISTS " + col), "model_usage 缺少版本列：" + col);
        }
        assertFalse(v222.toLowerCase().contains("tenantid"), "全程禁止引入 tenantId（ADR-029）");
    }

    @Test
    @DisplayName("契约副本与迁移内容一致（docs/contracts/db）")
    void contractCopiesPresent() throws IOException {
        assertTrue(read(V151_CONTRACT).contains("model_alias"), "V151 契约副本须描述别名重命名");
        assertTrue(read(V156_CONTRACT).contains("model_default_plan"), "V156 契约副本须描述删表");
        assertTrue(read(V222_CONTRACT).contains("binding_version_no"), "V222 契约副本须描述版本列");
    }

    @Test
    @DisplayName("默认方案读取方已切换到别名解析（ModelDefaultPlan 三层封装移除）")
    void defaultPlanLayerRemoved() throws IOException {
        assertFalse(Files.exists(repoRoot.resolve(
                        "tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                                + "/com/tioka/model/infrastructure/ModelDefaultPlanRepository.java")),
                "V156 后 ModelDefaultPlanRepository 必须移除");
        assertFalse(Files.exists(repoRoot.resolve(
                        "tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                                + "/com/tioka/model/infrastructure/persistence/ModelDefaultPlanDO.java")),
                "V156 后 ModelDefaultPlanDO 必须移除");
        String resolver = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/application/gateway/ModelBindingResolver.java");
        assertTrue(resolver.contains("findAliasByName"), "Resolver 必须经别名解析");
        assertTrue(resolver.contains("sysAliasName"), "Resolver 必须按 purpose 解析 sys.* 别名");
        assertFalse(resolver.contains("ModelDefaultPlan"), "Resolver 不得再依赖 model_default_plan");
    }

    @Test
    @DisplayName("调用链带版本：UsageRecordCommand / UsageRecorder / ModelUsageDO / ModelCatalog")
    void usageChainCarriesVersion() throws IOException {
        String cmd = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/application/gateway/UsageRecordCommand.java");
        for (String f : new String[]{"bindingVersionNo", "aliasVersionNo", "planVersionNo"}) {
            assertTrue(cmd.contains(f), "UsageRecordCommand 缺少版本字段：" + f);
        }
        String recorder = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/application/gateway/UsageRecorder.java");
        assertTrue(recorder.contains("setBindingVersionNo"), "UsageRecorder 必须落版本列");
        String usageDo = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/infrastructure/persistence/ModelUsageDO.java");
        assertTrue(usageDo.contains("bindingVersionNo"), "ModelUsageDO 必须带版本字段");
        String catalog = read("tioka-modules/tioka-module-model/tioka-module-model-api/src/main/java"
                + "/com/tioka/model/api/ModelCatalog.java");
        assertTrue(catalog.contains("currentVersionNo"), "ModelCatalog 快照必须带版本号（F11）");
    }

    private String read(String relative) throws IOException {
        return Files.readString(repoRoot.resolve(relative));
    }

    private static Path findRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        while (cwd != null && !Files.exists(cwd.resolve("tioka-server/src/main/resources/db/migration"))) {
            cwd = cwd.getParent();
        }
        if (cwd == null) {
            throw new IllegalStateException("找不到仓库根目录");
        }
        return cwd;
    }
}
