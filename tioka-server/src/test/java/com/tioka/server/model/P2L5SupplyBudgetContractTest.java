package com.tioka.server.model;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-L5（供给侧预算与 provider 分摊）离线源码守护：纯 JUnit5 + 反射 + nio 读文件，
 * 不启动 Spring、不依赖 Redis / PostgreSQL（离线验收；做法同 {@code P2L6TraceContinuityContractTest}）。
 *
 * <p>守护 GAP-L5-1（binding 月度预算熔断）与 GAP-L5-2（成本按 provider 分摊）两条链路
 * 不被静默回退：V218 迁移 → 用量写入透传 → 预算预检 → 分摊查询出口。
 * 端到端语义由 {@code QuotaIT} / {@code CostIT}（@Tag("it")，需 Docker/PG）在 CI 验证。
 */
class P2L5SupplyBudgetContractTest {

    private static Path repoRoot;

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("迁移 V218：provider_id 列 / binding 预算列 / gov_quota CHECK 扩展 cost_month")
    void migrationV218AddsSupplyBudgetSchema() throws IOException {
        Path sql = repoRoot.resolve(
                "tioka-server/src/main/resources/db/migration/V218__p2_model_supply_budget.sql");
        assertTrue(Files.isRegularFile(sql), "缺少 V218 迁移：" + sql);
        String text = Files.readString(sql, StandardCharsets.UTF_8);
        assertTrue(text.contains("ADD COLUMN provider_id VARCHAR(64)"),
                "V218 须为 model_usage 加 provider_id 列");
        assertTrue(text.contains("idx_usage_provider_ts"),
                "V218 须建 provider 分摊查询索引");
        assertTrue(text.contains("budget_token_month") && text.contains("budget_cost_month"),
                "V218 须为 model_binding 加月度预算列");
        assertTrue(text.contains("'cost_month'"),
                "V218 须扩展 gov_quota metric CHECK 支持 cost_month");
    }

    @Test
    @DisplayName("model_usage / UsageRecordCommand 携带 providerId（分摊透传）")
    void usageCarriesProviderId() throws Exception {
        Class<?> doClass = Class.forName("com.tioka.model.infrastructure.persistence.ModelUsageDO");
        assertTrue(fieldNames(doClass).contains("providerId"),
                "ModelUsageDO 必须携带 providerId（GAP-L5-2）");

        Class<?> cmd = Class.forName("com.tioka.model.application.gateway.UsageRecordCommand");
        assertTrue(fieldNames(cmd).contains("providerId"),
                "UsageRecordCommand 必须透传 providerId");
    }

    @Test
    @DisplayName("ModelBinding record 含 budgetTokenMonth / budgetCostMonth 预算组件")
    void bindingRecordHasBudgetFields() throws Exception {
        Class<?> binding = Class.forName("com.tioka.model.domain.ModelEntities$ModelBinding");
        java.util.List<String> components = Arrays.stream(binding.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertTrue(components.contains("budgetTokenMonth"),
                "ModelBinding 须有 budgetTokenMonth 组件（GAP-L5-1）");
        assertTrue(components.contains("budgetCostMonth"),
                "ModelBinding 须有 budgetCostMonth 组件（GAP-L5-1）");
    }

    @Test
    @DisplayName("ErrorCode 登记 MODEL_BUDGET_EXHAUSTED（HTTP 429）")
    void errorCodeRegistered() {
        Enum.valueOf(com.tioka.framework.error.ErrorCode.class, "MODEL_BUDGET_EXHAUSTED");
    }

    @Test
    @DisplayName("网关在 provider 调用前做预算预检并沿链熔断")
    void gatewayPrechecksBudgetBeforeCall() throws IOException {
        String gw = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/application/gateway/DefaultModelGateway.java");
        assertTrue(gw.contains("budgetExhausted"),
                "chatTracked 须在调用前做预算预检（候选跳过）");
        assertTrue(gw.contains("MODEL_BUDGET_EXHAUSTED"),
                "链耗尽须抛 MODEL_BUDGET_EXHAUSTED");
        assertTrue(gw.contains("budget.check(sel.binding()"),
                "embed 链路须做预算预检");
        String guard = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/application/gateway/ModelBudgetGuard.java");
        assertTrue(guard.contains("sumTokensByBinding") && guard.contains("sumCostByBinding"),
                "预算守卫须按 binding 聚合本月 token / 成本");
    }

    @Test
    @DisplayName("成本查询出口支持 groupBy=provider 分摊")
    void costQuerySupportsProviderAttribution() throws IOException {
        String svc = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java"
                + "/com/tioka/model/application/CostQueryService.java");
        assertTrue(svc.contains("summaryByProvider"),
                "CostQueryService 须支持 provider 分摊（GAP-L5-2）");

        String xml = read("tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/resources"
                + "/mapper/ModelUsageMapper.xml");
        assertTrue(xml.contains("summaryByProvider") && xml.contains("model_provider"),
                "ModelUsageMapper.xml 须含 summaryByProvider（LEFT JOIN model_provider）");
        assertTrue(xml.contains("scopeType} = 'provider'"),
                "trendByDay 须支持 provider 趋势维度");
    }

    private static java.util.List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }

    private static String read(String relative) throws IOException {
        Path file = repoRoot.resolve(relative);
        assertTrue(Files.isRegularFile(file), "缺少源文件：" + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** 同 P2L6TraceContinuityContractTest：从 user.dir 向上找仓库根。 */
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("tioka-modules"))
                    && Files.isDirectory(cur.resolve("docs"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "无法从 " + dir + " 向上找到仓库根（需同时含 tioka-modules 与 docs）");
    }
}
