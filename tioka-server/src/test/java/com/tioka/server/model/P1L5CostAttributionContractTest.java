package com.tioka.server.model;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1 / L5（模型网关配额与成本归集）离线契约测试：纯 JUnit5 + 反射，不启 Spring、
 * 不依赖 Redis / PostgreSQL（离线验收）。
 *
 * <p>本类只固化「代码现状与 L5 验收准则一致」的部分；供给侧月度预算与 provider 分摊
 * 已在 P2 落地（见 {@code P2L5SupplyBudgetContractTest} 与 V218 迁移）；
 * 成本 80% 预警、rerank 实现（MH-04）等缺口见
 * {@code docs/architecture/design-notes/P1-L5-gap-ledger.md}。
 */
class P1L5CostAttributionContractTest {

    private static final String MODEL_USAGE_DO = "com.tioka.model.infrastructure.persistence.ModelUsageDO";
    private static final String MODEL_GATEWAY = "com.tioka.model.api.gateway.ModelGateway";

    /** 含父类（AppendOnlyDO）在内的全量字段名。 */
    private static List<String> allFieldNames(Class<?> type) {
        return Arrays.stream(collectFields(type)).map(Field::getName).toList();
    }

    private static Field[] collectFields(Class<?> type) {
        Field[] own = type.getDeclaredFields();
        Class<?> superType = type.getSuperclass();
        if (superType == null || superType == Object.class) {
            return own;
        }
        Field[] sup = collectFields(superType);
        Field[] all = Arrays.copyOf(own, own.length + sup.length);
        System.arraycopy(sup, 0, all, own.length, sup.length);
        return all;
    }

    @Test
    @DisplayName("model_usage 权威隔离键为 workspaceId，不含 tenantId")
    void modelUsageKeysOnWorkspaceNotTenant() throws Exception {
        Class<?> doClass = Class.forName(MODEL_USAGE_DO);

        assertTrue(allFieldNames(doClass).contains("workspaceId"),
                "ModelUsageDO 必须携带 workspaceId（权威隔离字段）");
        assertFalse(allFieldNames(doClass).contains("tenantId"),
                "ModelUsageDO 不得引入 tenantId（ADR-029：权威隔离字段为 workspaceId）");

        TableName tableName = doClass.getAnnotation(TableName.class);
        assertNotNull(tableName, "ModelUsageDO 应有 @TableName");
        assertEquals("model_usage", tableName.value());
    }

    @Test
    @DisplayName("model_usage 携带 costMicros 与 traceId")
    void modelUsageCarriesCostAndTrace() throws Exception {
        Class<?> doClass = Class.forName(MODEL_USAGE_DO);
        List<String> fields = allFieldNames(doClass);

        assertTrue(fields.contains("costMicros"), "必须含 costMicros（微美分成本）");
        assertTrue(fields.contains("traceId"), "必须含 traceId（全链贯通）");
        // 成本归集的核心维度
        assertTrue(fields.contains("workspaceId"));
        assertTrue(fields.contains("userId"));
        assertTrue(fields.contains("agentId"));
        assertTrue(fields.contains("modelRef"));
        assertTrue(fields.contains("promptTokens"));
        assertTrue(fields.contains("completionTokens"));
        // P2-L5（GAP-L5-2 已落地）：供给侧 provider 分摊维度
        assertTrue(fields.contains("providerId"),
                "P2-L5 落地后 model_usage 须携带 providerId（供给侧分摊）");
    }

    @Test
    @DisplayName("ModelGateway 接口签名未漂移：chat / embed / rerank")
    void gatewayInterfaceStable() throws Exception {
        Class<?> gateway = Class.forName(MODEL_GATEWAY);
        List<String> methodNames = Arrays.stream(gateway.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();

        assertTrue(methodNames.contains("chat"), "ModelGateway 应声明 chat");
        assertTrue(methodNames.contains("embed"), "ModelGateway 应声明 embed");
        assertTrue(methodNames.contains("rerank"), "ModelGateway 应声明 rerank");
    }

    /**
     * 只断言接口层面存在 rerank 契约，不触发真实模型调用。
     * 实现 {@code DefaultModelGateway.rerank} 抛 {@code NotImplemented.of("ModelGateway.rerank","MH-04")}
     * 为已知缺口（DefaultModelGateway.java:385-386），登记于 P1-L5-gap-ledger.md，归 P2。
     */
    @Test
    @DisplayName("rerank 契约在接口层存在（实现为 MH-04 已知缺口，归 P2）")
    void rerankIsKnownGapMH04() throws Exception {
        Class<?> gateway = Class.forName(MODEL_GATEWAY);
        long rerankCount = Arrays.stream(gateway.getDeclaredMethods())
                .filter(m -> "rerank".equals(m.getName())).count();
        assertEquals(1, rerankCount, "ModelGateway.rerank 契约方法应存在且唯一");
    }
}
