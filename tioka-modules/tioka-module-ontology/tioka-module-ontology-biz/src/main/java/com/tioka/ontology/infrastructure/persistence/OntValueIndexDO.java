package com.tioka.ontology.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.tioka.ontology.infrastructure.PgTextArrayTypeHandler;

import java.util.List;

/**
 * {@code ont_value_index}（V172）：按路径查询与覆盖统计的派生索引，可由 model_json 重建。
 * 联合主键 (instance_key, version, path)——复合主键不走 @TableId，增删查走 mapper XML；
 * 无 workspace_id 列，登记 workspace-scope-exempt。
 */
@TableName(value = "ont_value_index", autoResultMap = true)
public class OntValueIndexDO extends TechnicalRecordDO {

    private String instanceKey;
    private Integer version;
    private String path;
    /** 取值状态归一口径：KNOWN / UNKNOWN / BLANK / NA / REVIEW（≤ VARCHAR(12)）。 */
    private String status;
    private String valueText;

    @TableField(typeHandler = PgTextArrayTypeHandler.class)
    private List<String> topics;

    public String getInstanceKey() {
        return instanceKey;
    }

    public void setInstanceKey(String instanceKey) {
        this.instanceKey = instanceKey;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getValueText() {
        return valueText;
    }

    public void setValueText(String valueText) {
        this.valueText = valueText;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }
}
