package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;
import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.DatasetResult;
import com.tioka.connector.api.dataset.PlannedQuery;
import com.tioka.connector.api.dataset.spi.DatasetSource;
import com.tioka.framework.error.BizException;
import com.tioka.framework.label.DataLabel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据集网关离线测试（DAT-01）：双区域行隔离、列掩码、filter 注入拒绝、
 * REST 后过滤与 DRYRUN/ENFORCE 双模式。存储与数据源用内存实现（离线优先惯例）。
 */
class DatasetGatewayIT {

    private static final String WS = "ws_1";

    private static DatasetDescriptor descriptor() {
        return new DatasetDescriptor("ds_1", "contracts", List.of(
                new ColumnSpec("contractId", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("region", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("amount", "decimal", DataLabel.CONFIDENTIAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("phone", "varchar", DataLabel.CONFIDENTIAL, ColumnSpec.Mask.PARTIAL),
                new ColumnSpec("secret_note", "varchar", DataLabel.SECRET, ColumnSpec.Mask.NONE)));
    }

    private static DatasetPolicyModel.PrincipalSnapshot eff() {
        return new DatasetPolicyModel.PrincipalSnapshot("u_1", List.of("sales"),
                Map.of("dept", "fin", "region", "cn-north"));
    }

    /** 内存数据集源：行级谓词按计划过滤（模拟 JDBC WHERE / REST postFilter 语义）。 */
    private static class InMemorySource implements DatasetSource {
        final String datasetId;
        final List<Map<String, Object>> rows;

        InMemorySource(String datasetId, List<Map<String, Object>> rows) {
            this.datasetId = datasetId;
            this.rows = rows;
        }

        @Override public String datasetId() { return datasetId; }

        @Override public DatasetDescriptor describe() { return descriptor(); }

        @Override
        public List<Map<String, Object>> execute(PlannedQuery plan, DatasetQuery query) {
            return rows.stream()
                    // 内存源模拟谓词下推：postFilter 与 whereSql 同源（网关二次过滤演示幂等）
                    .filter(r -> plan.postFilter() == null || plan.postFilter().test(r))
                    .limit(query.limit())
                    .toList();
        }
    }

    private static class InMemoryStore implements DatasetStore {
        Registration registration;

        void put(Registration reg) { this.registration = reg; }

        @Override public Optional<Registration> find(String workspaceId, String name) {
            if (registration != null && registration.workspaceId().equals(workspaceId)
                    && registration.name().equals(name)) {
                return Optional.of(registration);
            }
            return Optional.empty();
        }

        @Override public void save(Registration reg) { this.registration = reg; }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<com.tioka.system.api.audit.AuditApi> noAudit() {
        return (ObjectProvider<com.tioka.system.api.audit.AuditApi>) org.mockito.Mockito.mock(
                ObjectProvider.class);
    }

    private static DatasetGateway gateway(InMemoryStore store, InMemorySource source) {
        return new DatasetGateway(store, new DatasetPolicyCompiler(), List.of(source),
                new ColumnMasker("salt-1"), noAudit(), null);
    }

    private static DatasetStore.Registration register(InMemoryStore store, String mode,
                                                      String sourceKind,
                                                      DatasetPolicyModel.DatasetPolicy policy) {
        Map<String, Object> sourceCfg = Map.of("kind", sourceKind, "sql", "SELECT * FROM contracts");
        DatasetStore.Registration reg = new DatasetStore.Registration(
                "ds_1", WS, "conn_1", "contracts", descriptor(), sourceCfg, policy, mode, 1);
        store.put(reg);
        return reg;
    }

    private static List<Map<String, Object>> seedRows() {
        return List.of(
                row("C1", "cn-north", 100, "13812345678", "内部备注甲"),
                row("C2", "cn-north", 900, "13900001111", "内部备注乙"),
                row("C3", "cn-south", 500, "18612345678", "内部备注丙"));
    }

    private static Map<String, Object> row(String id, String region, long amount,
                                           String phone, String note) {
        return new java.util.LinkedHashMap<>(Map.of(
                "contractId", id, "region", region, "amount", amount,
                "phone", phone, "secret_note", note));
    }

    // ---------- 双区域行隔离（内存源模拟 REST postFilter 语义；JDBC 下推语义归 CI） ----------
    @Test
    void dualRegionRowIsolation() {
        InMemoryStore store = new InMemoryStore();
        InMemorySource source = new InMemorySource("ds_1", seedRows());
        DatasetGateway gateway = gateway(store, source);
        register(store, "ENFORCE", "rest",
                // 只授权 cn-north 区域
                policyOf(null, "row.region == 'cn-north'"));

        DatasetResult r = gateway.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), eff(), null);
        // cn-south 行被策略拦截
        assertEquals(2, r.rows().size());
        assertTrue(r.rows().stream().allMatch(m -> "cn-north".equals(m.get("region"))));
        assertFalse(r.rows().stream().anyMatch(m -> "C3".equals(m.get("contractId"))));

        // 另一区域主体（region=cn-south）只见 cn-south 行
        DatasetPolicyModel.PrincipalSnapshot south = new DatasetPolicyModel.PrincipalSnapshot(
                "u_2", List.of("sales"), Map.of("dept", "fin", "region", "cn-south"));
        DatasetPolicyModel.DatasetPolicy scoped = new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule("principal.attrs.region == 'cn-south'",
                        "row.region == principal.attrs.region")),
                List.of());
        register(store, "ENFORCE", "rest", scoped);
        DatasetResult r2 = gateway.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), south, null);
        assertEquals(1, r2.rows().size());
        assertEquals("C3", r2.rows().get(0).get("contractId"));
    }

    // ---------- 列掩码 ----------
    @Test
    void columnMasksAppliedAndLabelComputed() {
        InMemoryStore store = new InMemoryStore();
        DatasetGateway gateway = gateway(store, new InMemorySource("ds_1", seedRows()));
        // phone PARTIAL（列缺省掩码）+ secret_note HASH（列规则）
        DatasetPolicyModel.DatasetPolicy p = new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(null, "row.region == 'cn-north'")),
                List.of(new DatasetPolicyModel.ColumnRule("secret_note", null, ColumnSpec.Mask.HASH)));
        register(store, "ENFORCE", "rest", p);

        DatasetResult r = gateway.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), eff(), null);
        assertEquals(2, r.rows().size());
        // PARTIAL：前 3 后 4
        assertEquals("138****5678", r.rows().get(0).get("phone"));
        // HASH：16 位 hex 且非原值
        String maskedNote = String.valueOf(r.rows().get(0).get("secret_note"));
        assertTrue(maskedNote.matches("[0-9a-f]{16}"));
        assertNotEquals("内部备注甲", maskedNote);
        // maskedColumns 审计只记列名
        assertTrue(r.maskedColumns().contains("phone"));
        assertTrue(r.maskedColumns().contains("secret_note"));
        // 结果标签 = select 列密级最高值（secret_note=SECRET 出现 → SECRET）
        assertEquals(DataLabel.SECRET, r.label());
    }

    @Test
    void nullifyAndDropColumns() {
        InMemoryStore store = new InMemoryStore();
        DatasetGateway gateway = gateway(store, new InMemorySource("ds_1", seedRows()));
        DatasetPolicyModel.DatasetPolicy p = new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(null, "row.region == 'cn-north'")),
                List.of(new DatasetPolicyModel.ColumnRule("secret_note", null, ColumnSpec.Mask.NULLIFY),
                        new DatasetPolicyModel.ColumnRule("phone", null, ColumnSpec.Mask.DROP)));
        register(store, "ENFORCE", "rest", p);

        DatasetResult r = gateway.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), eff(), null);
        // NULLIFY：列在但值为 null
        assertNull(r.rows().get(0).get("secret_note"));
        // DROP：列整个不返回
        assertFalse(r.rows().get(0).containsKey("phone"));
        // NULLIFY 列仍在 select（值为 null）→ 标签仍含 SECRET
        assertEquals(DataLabel.SECRET, r.label());
    }

    // ---------- filter 注入拒绝 ----------
    @Test
    void sqlInjectionInFilterRejected() {
        InMemoryStore store = new InMemoryStore();
        DatasetGateway gateway = gateway(store, new InMemorySource("ds_1", seedRows()));
        register(store, "ENFORCE", "rest",
                policyOf(null, "row.region == 'cn-north'"));

        // 经典注入串：CEL 白名单编译直接拒绝（' OR '1'='1 非合法 CEL）
        assertThrows(BizException.class, () -> gateway.query(WS, "contracts",
                new DatasetQuery("row.region == 'cn-north' OR '1'='1", List.of(), 100),
                eff(), null));
        assertThrows(BizException.class, () -> gateway.query(WS, "contracts",
                new DatasetQuery("row.region == 'x'; DROP TABLE contracts; --", List.of(), 100),
                eff(), null));
        assertThrows(BizException.class, () -> gateway.query(WS, "contracts",
                new DatasetQuery("row.owner.upper() == 'ADMIN'", List.of(), 100),
                eff(), null));

        // 合法 filter 正常编译执行（> 不可下推 → postFilter 网关内过滤）；谓词值全参数化
        DatasetResult ok = gateway.query(WS, "contracts",
                new DatasetQuery("row.amount > 200", List.of(), 100), eff(), null);
        assertEquals(1, ok.rows().size());
        assertEquals("C2", ok.rows().get(0).get("contractId"));
    }

    // ---------- REST 后过滤与模式 ----------
    @Test
    void restPostFilterMarksFactAndEnforceFilters() {
        InMemoryStore store = new InMemoryStore();
        // 内存源不做下推过滤 → 全部行进入网关，postFilter 在网关内执行
        InMemorySource source = new InMemorySource("ds_1", seedRows()) {
            @Override
            public List<Map<String, Object>> execute(PlannedQuery plan, DatasetQuery query) {
                return rows.stream().limit(query.limit()).toList();
            }
        };
        DatasetGateway gateway = gateway(store, source);
        DatasetPolicyModel.DatasetPolicy p = policyOf(null, "row.region == 'cn-north'");
        register(store, "ENFORCE", "rest", p);

        DatasetResult enforce = gateway.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), eff(), null);
        assertEquals(2, enforce.rows().size());
        assertTrue(enforce.postFiltered());

        // DRYRUN：不拦截但标记
        register(store, "DRYRUN", "rest", p);
        DatasetResult dry = gateway.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), eff(), null);
        assertEquals(3, dry.rows().size());
        assertTrue(dry.postFiltered());
    }

    @Test
    void unknownDatasetOrSourceFails() {
        InMemoryStore store = new InMemoryStore();
        DatasetGateway gateway = gateway(store, new InMemorySource("ds_1", seedRows()));
        register(store, "ENFORCE", "jdbc", policyOf(null, "row.region == 'cn-north'"));

        // 数据集不存在
        assertThrows(BizException.class, () -> gateway.query(WS, "nope",
                new DatasetQuery(null, List.of(), 100), eff(), null));
        // 源未注册
        register(store, "ENFORCE", "jdbc", policyOf(null, "row.region == 'cn-north'"));
        DatasetGateway noSource = new DatasetGateway(store, new DatasetPolicyCompiler(),
                List.of(), new ColumnMasker("s"), noAudit(), null);
        assertThrows(BizException.class, () -> noSource.query(WS, "contracts",
                new DatasetQuery(null, List.of(), 100), eff(), null));
    }

    private static DatasetPolicyModel.DatasetPolicy policyOf(String when, String predicate) {
        return new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(when, predicate)), List.of());
    }
}
