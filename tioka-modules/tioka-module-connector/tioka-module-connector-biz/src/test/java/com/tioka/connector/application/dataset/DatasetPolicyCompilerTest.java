package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;
import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.PlannedQuery;
import com.tioka.framework.error.BizException;
import com.tioka.framework.label.DataLabel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CEL 子集白名单与编译表驱动测试（DAT-01 / M3-02「CEL 子集与编译」）。
 * 纯离线：不依赖存储与数据源。
 */
class DatasetPolicyCompilerTest {

    private final DatasetPolicyCompiler compiler = new DatasetPolicyCompiler();

    private static DatasetPolicyModel.PrincipalSnapshot eff() {
        return new DatasetPolicyModel.PrincipalSnapshot("u_1", List.of("sales"),
                Map.of("dept", "fin", "region", "cn-north"));
    }

    private static DatasetDescriptor descriptor() {
        return new DatasetDescriptor("ds_1", "contracts", List.of(
                new ColumnSpec("contractId", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("region", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("amount", "decimal", DataLabel.CONFIDENTIAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("owner", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE)));
    }

    private static DatasetPolicyModel.DatasetPolicy policy(String when, String predicate) {
        return new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(when, predicate)), List.of());
    }

    // ---------- validate：合法子集 ----------
    @Test
    void acceptsWhitelistedSubset() {
        // 全部合法形式不抛异常
        compiler.validate("row.region == 'cn-north'");
        compiler.validate("row.amount >= 1000 && row.region != 'cn-south'");
        compiler.validate("row.region in ['cn-north', 'cn-south']");
        compiler.validate("row.owner.startsWith('z')");
        compiler.validate("principal.attrs.dept == 'fin'");
        compiler.validate("principal.roles == ['sales']");
        compiler.validate("!(row.region == 'cn-south') || row.amount < 50");
        compiler.validate("principal.attrs.region == row.region");
    }

    // ---------- validate：白名单外拒绝 ----------
    @Test
    void rejectsOutsideWhitelist() {
        // 白名单外函数调用
        assertThrows(BizException.class, () -> compiler.validate("row.owner.upper() == 'Z'"));
        // 字符串拼接（+ 不在白名单）
        assertThrows(BizException.class, () -> compiler.validate("row.region + 'x' == 'cn-northx'"));
        // 裸标识符（非 row./principal. 根）
        assertThrows(BizException.class, () -> compiler.validate("name == 'zhang'"));
        // 未知根
        assertThrows(BizException.class, () -> compiler.validate("user.name == 'zhang'"));
        // 语法错误
        assertThrows(BizException.class, () -> compiler.validate("row.region ==="));
        // 空表达式
        assertThrows(BizException.class, () -> compiler.validate("  "));
        assertThrows(BizException.class, () -> compiler.validate(null));
    }

    // ---------- compile：JDBC 模式 ----------
    @Test
    void jdbcCompileParameterizesAndSubstitutesPrincipal() {
        PlannedQuery plan = compiler.compile(
                policy(null, "row.region == principal.attrs.region && row.amount > 100"),
                eff(), Map.of("contractId", List.of("C1", "C2")),
                new DatasetQuery("row.owner.startsWith('z')", List.of(), 100),
                descriptor(), false);

        // principal 编译期代入常量 → 参数绑定；row.* → t."col"
        assertTrue(plan.whereSql().contains("t.\"region\""));
        assertTrue(plan.whereSql().contains("t.\"amount\""));
        assertTrue(plan.whereSql().contains("t.\"owner\" LIKE ?"));
        // 参数化：谓词值全部进 params，SQL 里零字面量拼接
        assertTrue(plan.params().contains("cn-north"));
        assertTrue(plan.params().contains(100L));
        assertTrue(plan.params().contains("z%"));
        // grant.scope 同名列 → 常量 IN 下推
        assertTrue(plan.whereSql().contains("IN"));
        assertTrue(plan.params().contains("C1"));
        assertTrue(plan.params().contains("C2"));
        // JDBC 模式无 restParams / postFilter
        assertTrue(plan.restParams().isEmpty());
        assertNull(plan.postFilter());
        // 全列选择（无 DROP）
        assertEquals(List.of("contractId", "region", "amount", "owner"), plan.selectColumns());
    }

    @Test
    void jdbcZeroRowsWhenNoRuleMatches() {
        // when 求值为假 → 零行（默认拒绝）
        PlannedQuery plan = compiler.compile(
                policy("principal.attrs.dept == 'hr'", "row.region == 'cn-north'"),
                eff(), null, new DatasetQuery(null, List.of(), 100), descriptor(), false);
        assertEquals("1 = 0", plan.whereSql());
        assertTrue(plan.params().isEmpty());
    }

    @Test
    void jdbcColumnsIntersectAndDrop() {
        DatasetPolicyModel.DatasetPolicy p = new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(null, "row.region == 'cn-north'")),
                List.of(new DatasetPolicyModel.ColumnRule("amount", null, ColumnSpec.Mask.DROP)));
        PlannedQuery plan = compiler.compile(p, eff(), null,
                new DatasetQuery(null, List.of("amount", "region"), 100), descriptor(), false);
        // DROP 列从 select 去掉；未知列拒绝
        assertEquals(List.of("region"), plan.selectColumns());
        assertThrows(BizException.class, () -> compiler.compile(p, eff(), null,
                new DatasetQuery(null, List.of("ghost"), 100), descriptor(), false));
    }

    // ---------- compile：REST 模式 ----------
    @Test
    void restCompilePushesEqualityAndPostFilters() {
        PlannedQuery plan = compiler.compile(
                policy(null, "row.region == principal.attrs.region"),
                eff(), Map.of("contractId", List.of("C1")),
                new DatasetQuery("row.owner == 'zhang' && row.amount > 50", List.of(), 100),
                descriptor(), true);

        // 等值下推：owner == 'zhang'（常量）+ grantScope contractId
        assertEquals("zhang", plan.restParams().get("owner"));
        assertEquals("C1", plan.restParams().get("contractId"));
        assertTrue(plan.postFilterNeeded());
        // postFilter：row 级条件在插件内执行
        Map<String, Object> hit = Map.of("region", "cn-north", "amount", 100L,
                "owner", "zhang", "contractId", "C1");
        Map<String, Object> missRegion = Map.of("region", "cn-south", "amount", 100L,
                "owner", "zhang", "contractId", "C1");
        Map<String, Object> missAmount = Map.of("region", "cn-north", "amount", 10L,
                "owner", "zhang", "contractId", "C1");
        assertTrue(plan.postFilter().test(hit));
        assertFalse(plan.postFilter().test(missRegion));
        assertFalse(plan.postFilter().test(missAmount));
    }

    @Test
    void effectiveMasksLaterRuleOverrides() {
        DatasetPolicyModel.DatasetPolicy p = new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(null, "row.region == 'cn-north'")),
                List.of(new DatasetPolicyModel.ColumnRule("amount", null, ColumnSpec.Mask.PARTIAL),
                        new DatasetPolicyModel.ColumnRule("amount", "principal.roles == ['sales']",
                                ColumnSpec.Mask.HASH)));
        Map<String, ColumnSpec.Mask> masks = compiler.effectiveMasks(
                p, eff(), Map.of("amount", new ColumnSpec("amount", "decimal",
                        DataLabel.CONFIDENTIAL, ColumnSpec.Mask.NONE)));
        // 后规则覆盖前规则：roles 命中 → HASH
        assertEquals(ColumnSpec.Mask.HASH, masks.get("amount"));
        // 未知列引用拒绝
        DatasetPolicyModel.DatasetPolicy bad = new DatasetPolicyModel.DatasetPolicy(
                List.of(), List.of(new DatasetPolicyModel.ColumnRule("ghost", null, ColumnSpec.Mask.PARTIAL)));
        assertThrows(BizException.class, () -> compiler.effectiveMasks(
                bad, eff(), Map.of("amount", new ColumnSpec("amount", "decimal",
                        DataLabel.CONFIDENTIAL, ColumnSpec.Mask.NONE))));
    }

    @Test
    void evalWhenBlankIsTrue() {
        assertTrue(compiler.evalWhen(null, eff()));
        assertTrue(compiler.evalWhen("", eff()));
        assertTrue(compiler.evalWhen("principal.roles == ['sales']", eff()));
        assertFalse(compiler.evalWhen("principal.roles == ['hr']", eff()));
    }

    @Test
    void queryLimitCappedAndDefaulted() {
        assertEquals(100, new DatasetQuery(null, null, 0).limit());
        assertEquals(1000, new DatasetQuery(null, null, 5000).limit());
        assertEquals(DataLabel.INTERNAL, descriptor().columns().get(0).label());
    }
}
