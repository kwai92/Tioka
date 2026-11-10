package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;

import java.util.List;
import java.util.Map;

/**
 * 数据集策略解析模型（DAT-01 / ADR-040「数据集与数据集策略」）。
 */
public final class DatasetPolicyModel {
    private DatasetPolicyModel() {}

    /** 行规则：when（只引用 principal）命中时，predicate（引用 row.*）生效；多条取并集，全不命中 = 零行。 */
    public record RowRule(String when, String predicate) {}

    /** 列规则：when 命中时该列按 mask 处理；未列出的列按列密级缺省掩码。 */
    public record ColumnRule(String column, String when, ColumnSpec.Mask mask) {}

    public record DatasetPolicy(List<RowRule> rows, List<ColumnRule> columns) {
        public DatasetPolicy {
            rows = rows == null ? List.of() : List.copyOf(rows);
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /** 链根主体快照（on_behalf_of 交集后的属性；M3-02 编译期代入常量）。 */
    public record PrincipalSnapshot(String userId, List<String> roles, Map<String, Object> attrs) {
        public PrincipalSnapshot {
            roles = roles == null ? List.of() : List.copyOf(roles);
            attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
        }

        public Map<String, Object> toCelActivation() {
            return Map.of(
                    "userId", userId == null ? "" : userId,
                    "roles", roles,
                    "attrs", attrs);
        }
    }

    /** SQL 片段 + 参数（全部参数化绑定）。 */
    public record SqlFragment(String sql, List<Object> params) {
        public SqlFragment {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }
}
