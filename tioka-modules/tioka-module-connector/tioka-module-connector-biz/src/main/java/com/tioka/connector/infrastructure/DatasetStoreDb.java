package com.tioka.connector.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.api.dataset.ColumnSpec;
import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.application.dataset.DatasetStore;
import com.tioka.connector.application.dataset.DatasetPolicyModel;
import com.tioka.connector.infrastructure.mapper.DatasetMapper;
import com.tioka.connector.infrastructure.mapper.DatasetPolicyMapper;
import com.tioka.connector.infrastructure.persistence.DatasetDO;
import com.tioka.connector.infrastructure.persistence.DatasetPolicyDO;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据集与策略 DB 存取（V163 data_dataset / data_dataset_policy，DAT-01）。
 * 组装 {@link DatasetStore.Registration}：数据集行 + 当前生效（最高版本 ACTIVE）策略。
 */
@Repository
public class DatasetStoreDb implements DatasetStore {

    private final DatasetMapper datasets;
    private final DatasetPolicyMapper policies;
    private final ObjectMapper mapper;

    public DatasetStoreDb(DatasetMapper datasets, DatasetPolicyMapper policies, ObjectMapper mapper) {
        this.datasets = datasets;
        this.policies = policies;
        this.mapper = mapper;
    }

    @Override
    public Optional<Registration> find(String workspaceId, String name) {
        if (workspaceId == null || name == null) {
            return Optional.empty();
        }
        DatasetDO row = datasets.selectOne(Wrappers.<DatasetDO>lambdaQuery()
                .eq(DatasetDO::getWorkspaceId, workspaceId)
                .eq(DatasetDO::getName, name)
                .eq(DatasetDO::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (row == null) {
            return Optional.empty();
        }
        DatasetPolicyDO policyRow = policies.selectOne(Wrappers.<DatasetPolicyDO>lambdaQuery()
                .eq(DatasetPolicyDO::getDatasetId, row.getId())
                .eq(DatasetPolicyDO::getStatus, "ACTIVE")
                .orderByDesc(DatasetPolicyDO::getVersion)
                .last("LIMIT 1"));
        return Optional.of(new Registration(
                row.getId(), row.getWorkspaceId(), row.getConnectorId(), row.getName(),
                descriptor(row.getColumnsJson(), row.getName()),
                mapOf(row.getSourceJson()),
                policy(policyRow),
                policyRow == null ? "DRYRUN" : policyRow.getMode(),
                policyRow == null ? 0 : policyRow.getVersion()));
    }

    @Override
    public void save(Registration registration) {
        throw new UnsupportedOperationException("数据集登记走管理面 API，M3 只读");
    }

    private DatasetDescriptor descriptor(String columnsJson, String name) {
        List<ColumnSpec> cols = new ArrayList<>();
        if (columnsJson != null) {
            try {
                List<Map<String, Object>> raw = mapper.readValue(columnsJson,
                        new TypeReference<>() {});
                for (Map<String, Object> c : raw) {
                    cols.add(new ColumnSpec(
                            String.valueOf(c.get("name")),
                            c.get("type") == null ? null : String.valueOf(c.get("type")),
                            c.get("label") == null ? null : com.tioka.framework.label.DataLabel.valueOf(
                                    String.valueOf(c.get("label"))),
                            c.get("defaultMask") == null ? null : ColumnSpec.Mask.valueOf(
                                    String.valueOf(c.get("defaultMask")))));
                }
            } catch (Exception e) {
                throw new IllegalStateException("data_dataset.columns_json 解析失败: " + e.getMessage(), e);
            }
        }
        return new DatasetDescriptor(null, name, cols);
    }

    private Map<String, Object> mapOf(String sourceJson) {
        if (sourceJson == null || sourceJson.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(sourceJson, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("data_dataset.source_json 解析失败: " + e.getMessage(), e);
        }
    }

    private DatasetPolicyModel.DatasetPolicy policy(DatasetPolicyDO row) {
        if (row == null) {
            return new DatasetPolicyModel.DatasetPolicy(List.of(), List.of());
        }
        try {
            List<DatasetPolicyModel.RowRule> rows = new ArrayList<>();
            if (row.getRowsJson() != null && !row.getRowsJson().isBlank()) {
                JsonNode arr = mapper.readTree(row.getRowsJson());
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        rows.add(new DatasetPolicyModel.RowRule(
                                text(n, "when"), text(n, "predicate")));
                    }
                }
            }
            List<DatasetPolicyModel.ColumnRule> cols = new ArrayList<>();
            if (row.getColsJson() != null && !row.getColsJson().isBlank()) {
                JsonNode arr = mapper.readTree(row.getColsJson());
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        cols.add(new DatasetPolicyModel.ColumnRule(
                                text(n, "column"), text(n, "when"),
                                n.hasNonNull("mask") ? ColumnSpec.Mask.valueOf(n.get("mask").asText()) : null));
                    }
                }
            }
            return new DatasetPolicyModel.DatasetPolicy(rows, cols);
        } catch (Exception e) {
            throw new IllegalStateException("data_dataset_policy 解析失败: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
