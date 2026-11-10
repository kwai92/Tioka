package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.DatasetDescriptor;

import java.util.Map;
import java.util.Optional;

/**
 * 数据集与策略存取端口（V163 data_dataset / data_dataset_policy）。
 * 生产实现走 DB；离线测试用内存实现。
 */
public interface DatasetStore {

    /** 数据集登记（含当前生效策略）。 */
    record Registration(
            String id,
            String workspaceId,
            String connectorId,
            String name,
            DatasetDescriptor descriptor,
            Map<String, Object> source,
            DatasetPolicyModel.DatasetPolicy policy,
            String mode,
            int policyVersion) {
    }

    Optional<Registration> find(String workspaceId, String name);

    void save(Registration registration);
}
