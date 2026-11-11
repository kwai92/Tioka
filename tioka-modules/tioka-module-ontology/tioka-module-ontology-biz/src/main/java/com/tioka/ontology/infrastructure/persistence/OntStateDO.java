package com.tioka.ontology.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

import java.time.Instant;

/**
 * {@code ont_state}（V173）：门店级折叠状态（ONT-07，M3-03 §12）。
 * 主键 store_key，一份门店一行当前折叠态；input_hash 为输入版本集合
 * {instanceKey:version} 排序后哈希（乱序同哈希，幂等去重）。
 * JSONB 列按 repo 惯例映射为 String（OntInstanceVersionDO 同口径）；
 * 无 workspace_id 列，登记 workspace-scope-exempt。
 */
@TableName(value = "ont_state")
public class OntStateDO extends TechnicalRecordDO {

    @TableId
    private String storeKey;
    private String packKey;
    /** 输入版本集合 {instanceKey:version} 排序后 SHA-256（hex）。 */
    private String inputHash;
    /** JSONB 列，按 repo 惯例映射为 String。 */
    private String stateJson;
    /** 折叠中进复核的项（R-3、R-4、R-5）；JSONB 列 → JSON 数组文本。 */
    private String reviewItems;
    private Instant foldedAt;
    private Boolean writtenBack;

    public String getStoreKey() {
        return storeKey;
    }

    public void setStoreKey(String storeKey) {
        this.storeKey = storeKey;
    }

    public String getPackKey() {
        return packKey;
    }

    public void setPackKey(String packKey) {
        this.packKey = packKey;
    }

    public String getInputHash() {
        return inputHash;
    }

    public void setInputHash(String inputHash) {
        this.inputHash = inputHash;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public String getReviewItems() {
        return reviewItems;
    }

    public void setReviewItems(String reviewItems) {
        this.reviewItems = reviewItems;
    }

    public Instant getFoldedAt() {
        return foldedAt;
    }

    public void setFoldedAt(Instant foldedAt) {
        this.foldedAt = foldedAt;
    }

    public Boolean getWrittenBack() {
        return writtenBack;
    }

    public void setWrittenBack(Boolean writtenBack) {
        this.writtenBack = writtenBack;
    }
}
