package com.tioka.ontology.application.query;

import com.tioka.connector.api.dataset.ColumnSpec;
import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.application.dataset.DatasetStore;
import com.tioka.connector.application.dataset.DatasetPolicyModel;
import com.tioka.framework.label.DataLabel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

/**
 * {@code ontology.instance} 数据集登记（ONT-03，M3-03 §8）：向 DatasetStore 注册
 * Registration（provider=ontology，行谓词口径 = acl_principals 与 principal 交集），
 * 供 DAT-01 治理面可见。查询执行不走 DatasetGateway——{@link ModelQueryService} 内
 * 实现行过滤（登记偏差：DatasetStoreDb.save 走管理面 API、M3 只读，此处 best-effort，
 * 不支持时记 WARN 跳过；DatasetPolicyCompiler 未复用亦未重写 CEL 解析，ACL 交集为
 * 内存行谓词，非 CEL）。
 */
public class OntologyInstanceDatasetRegistrar {

    private static final Logger log = LoggerFactory.getLogger(OntologyInstanceDatasetRegistrar.class);

    public static final String DATASET_NAME = "ontology.instance";

    private final ObjectProvider<DatasetStore> stores;

    public OntologyInstanceDatasetRegistrar(ObjectProvider<DatasetStore> stores) {
        this.stores = stores;
    }

    /** 装配时调用（@Bean initMethod）；失败不影响启动。 */
    public void register() {
        DatasetStore store = stores.getIfAvailable();
        if (store == null) {
            log.info("ontology.instance 数据集登记跳过：DatasetStore 未装配（connector DAT-01 未启用）");
            return;
        }
        DatasetDescriptor descriptor = new DatasetDescriptor(null, DATASET_NAME, List.of(
                new ColumnSpec("instance_key", "varchar", DataLabel.CONFIDENTIAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("version", "integer", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("status", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("def_key", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("store_key", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("acl_principals", "varchar[]", DataLabel.INTERNAL, ColumnSpec.Mask.NONE),
                new ColumnSpec("data_label", "varchar", DataLabel.INTERNAL, ColumnSpec.Mask.NONE)));
        // 行谓词口径：acl_principals 与 principal（经 on_behalf_of 交集）标识有交集才可见；
        // 执行在 ModelQueryService（内存行过滤），此登记面向治理面描述同一谓词。
        DatasetPolicyModel.DatasetPolicy policy = new DatasetPolicyModel.DatasetPolicy(
                List.of(new DatasetPolicyModel.RowRule(
                        null,
                        "row.acl_principals.exists(p => p == principal.userId || p == principal.onBehalfOf || principal.roles.contains(p))")),
                List.of());
        DatasetStore.Registration registration = new DatasetStore.Registration(
                "ontology", null, "ontology", DATASET_NAME,
                descriptor,
                Map.of("kind", "ontology", "provider", "ontology"),
                policy,
                "ENFORCE",
                1);
        try {
            store.save(registration);
        } catch (RuntimeException e) {
            // DatasetStoreDb.save = 管理面 API、M3 只读 → 记录降级不阻断（登记偏差）
            log.warn("ontology.instance 数据集登记未落库（{}）；查询行过滤在 ModelQueryService 内执行", e.toString());
        }
    }
}
