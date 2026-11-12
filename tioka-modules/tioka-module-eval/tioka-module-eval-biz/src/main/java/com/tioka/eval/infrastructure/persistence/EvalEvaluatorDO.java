package com.tioka.eval.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.TechnicalRecordDO;

/**
 * EVO-02 / V176：评估器实例（ADR-031）。workspace_id NULL = 平台内置（跨空间共享）；
 * type 对应 Evaluator SPI 的 type()；builtin 行由 V176 迁移种子注册。
 * ADR-031 原文无审计列，挂 TechnicalRecordDO 空基类；查询经
 * {@code @InterceptorIgnore} 旁路 ScopeLine，空间隔离在服务层按 workspace_id 校验。
 */
@TableName("eval_evaluator")
public class EvalEvaluatorDO extends TechnicalRecordDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String type;
    private String name;
    private String configJson;
    private Boolean builtin;
    /** 评分器版本（C-18 EVO-07 / V225 落列；ADR-068 第 3 节，消费面 EVO-09 接入） */
    private String version;
    /** 评分器配置指纹（config_json 变化即新版本） */
    private String configHash;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public Boolean getBuiltin() { return builtin; }
    public void setBuiltin(Boolean builtin) { this.builtin = builtin; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String configHash) { this.configHash = configHash; }
}
