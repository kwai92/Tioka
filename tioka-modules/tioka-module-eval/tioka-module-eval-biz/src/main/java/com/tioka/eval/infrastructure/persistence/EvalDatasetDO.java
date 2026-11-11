package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * EVO-02 / V176：评测数据集（ADR-031）。版本化；schema_json 为字段 Schema 自定义
 * （text / number / json）。审计四列 + deleted 与 BaseDO 对齐。
 */
@TableName("eval_dataset")
public class EvalDatasetDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String name;
    private String schemaJson;
    private Integer latestVersion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSchemaJson() { return schemaJson; }
    public void setSchemaJson(String schemaJson) { this.schemaJson = schemaJson; }
    public Integer getLatestVersion() { return latestVersion; }
    public void setLatestVersion(Integer latestVersion) { this.latestVersion = latestVersion; }
}
