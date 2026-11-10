package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 草稿（M3-03 §7 / ont_draft 行的应用层视图）。
 *
 * @param instanceKey    业务键 {@code {domain}:{门店编码}:{合同号}}
 * @param defKey         模型定义键
 * @param defVersion     模型定义版本
 * @param storeKey       门店键
 * @param docIds         证据文档
 * @param modelJson      整份模型
 * @param validationJson REJECT / REVIEW 清单
 * @param buildRunId     @model.build 的 Flow Run
 * @param bizDraftRef    proposeModelDraft 的返回（业务系统侧）
 * @param revision       乐观锁版本（读取后 +1 提交）
 */
public record ModelDraft(
        String instanceKey,
        String defKey,
        int defVersion,
        String storeKey,
        List<String> docIds,
        JsonNode modelJson,
        JsonNode validationJson,
        String buildRunId,
        String bizDraftRef,
        int revision) {

    public ModelDraft {
        if (docIds == null) {
            docIds = List.of();
        }
    }
}
