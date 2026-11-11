package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;
import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.DatasetResult;
import com.tioka.connector.api.dataset.PlannedQuery;
import com.tioka.connector.api.dataset.spi.DatasetSource;
import com.tioka.connector.application.dataset.jdbc.JdbcDatasetSourceFactory;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.label.DataLabel;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 数据集网关（DAT-01 / M3-02）：Agent 取数业务数据的唯一入口——
 * ACL → 授予 → 策略 → 执行 → 掩码 → 打标签 → 审计。
 *
 * <p>DRYRUN 口径（登记简化）：行过滤只记录「将被过滤行数」不拦截，列掩码仍执行；
 * ENFORCE 正常拦截。结果标签 = 出现列的列密级最高值（ADR-040 标签来源）。
 */
@Service
public class DatasetGateway {

    private static final Logger log = LoggerFactory.getLogger(DatasetGateway.class);

    private final DatasetStore store;
    private final DatasetPolicyCompiler compiler;
    private final List<DatasetSource> sources;
    private final ColumnMasker masker;
    private final ObjectProvider<AuditApi> auditApi;
    private final JdbcDatasetSourceFactory jdbcFactory;

    public DatasetGateway(DatasetStore store,
                          DatasetPolicyCompiler compiler,
                          List<DatasetSource> sources,
                          ColumnMasker masker,
                          ObjectProvider<AuditApi> auditApi,
                          JdbcDatasetSourceFactory jdbcFactory) {
        this.store = store;
        this.compiler = compiler;
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.masker = masker;
        this.auditApi = auditApi;
        this.jdbcFactory = jdbcFactory;
    }

    public DatasetResult query(String workspaceId,
                               String datasetName,
                               DatasetQuery query,
                               DatasetPolicyModel.PrincipalSnapshot eff,
                               Map<String, List<String>> grantScope) {
        DatasetStore.Registration reg = store.find(workspaceId, datasetName)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "数据集不存在: " + datasetName));
        DatasetSource source = sourceOf(reg);
        boolean restMode = "rest".equalsIgnoreCase(String.valueOf(
                reg.source() == null ? null : reg.source().get("kind")));
        PlannedQuery plan = compiler.compile(reg.policy(), eff, grantScope, query,
                reg.descriptor(), restMode);

        List<Map<String, Object>> rawRows = source.execute(plan, query);
        boolean enforce = "ENFORCE".equalsIgnoreCase(reg.mode());

        List<Map<String, Object>> rows = new ArrayList<>();
        int wouldFilter = 0;
        Set<String> maskedColumns = new LinkedHashSet<>();
        for (Map<String, Object> raw : rawRows) {
            Map<String, Object> row = maskRow(raw, plan, reg.descriptor(), maskedColumns);
            if (plan.postFilter() != null && !plan.postFilter().test(row)) {
                wouldFilter++;
                if (enforce) {
                    continue;
                }
            }
            rows.add(row);
        }

        DataLabel label = resultLabel(plan, reg.descriptor());
        // REST 后过滤事实标记（M3-02 restPostFilter 口径）
        boolean postFiltered = restMode && plan.postFilter() != null && wouldFilter > 0;
        audit(reg, query, plan, rows.size(), wouldFilter, maskedColumns, enforce, label);

        return new DatasetResult(keepOnlySelect(rows, plan), label,
                List.copyOf(maskedColumns), postFiltered);
    }

    private DatasetSource sourceOf(DatasetStore.Registration reg) {
        for (DatasetSource s : sources) {
            if (s.datasetId().equals(reg.id())) {
                return s;
            }
        }
        // 静态注册源未命中：JDBC 型按登记动态构建
        String kind = String.valueOf(reg.source() == null ? null : reg.source().get("kind"));
        if ("jdbc".equalsIgnoreCase(kind) && jdbcFactory != null) {
            return jdbcFactory.create(reg);
        }
        throw new BizException(ErrorCode.NOT_FOUND, "数据集源未注册: " + reg.id());
    }

    private Map<String, Object> maskRow(Map<String, Object> raw, PlannedQuery plan,
                                        DatasetDescriptor descriptor, Set<String> maskedColumns) {
        Map<String, Object> row = ColumnMasker.copyOf(raw);
        for (Map.Entry<String, ColumnSpec.Mask> e : plan.masks().entrySet()) {
            if (!row.containsKey(e.getKey()) || row.get(e.getKey()) == null) {
                continue;
            }
            if (e.getValue() == ColumnSpec.Mask.PARTIAL) {
                row.put(e.getKey(), ColumnMasker.partialFor(specOf(descriptor, e.getKey()), row.get(e.getKey())));
            } else if (e.getValue() == ColumnSpec.Mask.HASH) {
                row.put(e.getKey(), masker.hash(row.get(e.getKey())));
            } else if (e.getValue() == ColumnSpec.Mask.NULLIFY) {
                row.put(e.getKey(), null);
            }
            // C-17 修复：NONE = 策略命中但无需掩码，不应计入「被掩码列」
            if (e.getValue() != ColumnSpec.Mask.NONE) {
                maskedColumns.add(e.getKey());
            }
        }
        return row;
    }

    private static ColumnSpec specOf(DatasetDescriptor descriptor, String col) {
        for (ColumnSpec c : descriptor.columns()) {
            if (c.name().equals(col)) {
                return c;
            }
        }
        return null;
    }

    /** 结果标签 = select 列的列密级最高值。 */
    private DataLabel resultLabel(PlannedQuery plan, DatasetDescriptor descriptor) {
        DataLabel label = DataLabel.PUBLIC;
        for (String col : plan.selectColumns()) {
            ColumnSpec spec = specOf(descriptor, col);
            if (spec != null) {
                label = DataLabel.max(label, spec.label());
            }
        }
        return label;
    }

    private static List<Map<String, Object>> keepOnlySelect(List<Map<String, Object>> rows,
                                                            PlannedQuery plan) {
        Set<String> select = new LinkedHashSet<>(plan.selectColumns());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> r = new LinkedHashMap<>();
            for (String col : select) {
                r.put(col, row.get(col));
            }
            out.add(r);
        }
        return out;
    }

    private void audit(DatasetStore.Registration reg, DatasetQuery query, PlannedQuery plan,
                       int returned, int filtered, Set<String> masked, boolean enforce,
                       DataLabel label) {
        AuditApi api = auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("datasetId", reg.id());
        details.put("policyVersion", reg.policyVersion());
        details.put("mode", reg.mode());
        details.put("returnedRows", returned);
        details.put("filteredRows", enforce ? filtered : 0);
        details.put("dryrunWouldFilter", enforce ? 0 : filtered);
        details.put("maskedColumns", List.copyOf(masked));
        details.put("postFiltered", plan.postFilter() != null);
        details.put("limit", query.limit());
        AuditEvent event = AuditEvent.builder()
                .ts(Instant.now())
                .action("data.query")
                .resourceType("dataset")
                .resourceId(reg.id())
                .outcome(AuditOutcome.SUCCESS)
                .details(details)
                .build();
        try {
            api.record(event);
        } catch (RuntimeException e) {
            log.warn("data.query 审计写入失败: {}", e.toString());
        }
    }
}
