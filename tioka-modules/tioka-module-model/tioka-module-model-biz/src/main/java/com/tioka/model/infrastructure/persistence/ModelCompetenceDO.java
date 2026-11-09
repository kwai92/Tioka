package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;

/**
 * MH-07 胜任度记录（V214 {@code model_competence}；ADR-024：Turn 结束打 0–1 分，M3 只记录不参与决策）。
 * UNIQUE(turn_id)：每 Turn 一条打分（ADR 数据模型 turn_id 主键语义）。
 */
@TableName("model_competence")
public class ModelCompetenceDO extends AppendOnlyDO {

    @TableId(type = IdType.INPUT)
    private String id;
    private String workspaceId;
    private String bindingId;
    private String turnId;
    private Double score;
    private String source;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getBindingId() { return bindingId; }
    public void setBindingId(String bindingId) { this.bindingId = bindingId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
}
