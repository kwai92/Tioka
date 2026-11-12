package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * EVO-02 / V176：评测数据集（ADR-031）。版本化；schema_json 为字段 Schema 自定义
 * （text / number / json）。审计四列 + deleted 与 BaseDO 对齐。
 * C-18 EVO-07 / V225：tier 分层（SMOKE/DEV/SEALED/REGRESSION/SPECIAL，ADR-068 第 1 节）
 * 与 unit_key_path 材料单元键路径。
 */
@TableName("eval_dataset")
public class EvalDatasetDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String name;
    private String schemaJson;
    private Integer latestVersion;
    /** SMOKE / DEV / SEALED / REGRESSION / SPECIAL（ADR-068 第 1 节） */
    private String tier;
    /** 材料单元键路径（如 contract_key）；NULL = 未声明 */
    private String unitKeyPath;

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
    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }
    public String getUnitKeyPath() { return unitKeyPath; }
    public void setUnitKeyPath(String unitKeyPath) { this.unitKeyPath = unitKeyPath; }
}
