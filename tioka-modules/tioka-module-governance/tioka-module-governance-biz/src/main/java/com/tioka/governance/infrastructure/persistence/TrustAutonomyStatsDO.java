package com.tioka.governance.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.tioka.framework.persistence.TechnicalRecordDO;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

/**
 * {@code trust_autonomy_stats}（V203 / ADR-034「数据模型」）；30 天滚动窗口指标按日汇总。
 * 复合主键（agent_id, action_class, day）不走 {@code @TableId}，查询经仓储显式条件。
 */
@TableName("trust_autonomy_stats")
public class TrustAutonomyStatsDO extends TechnicalRecordDO {

    private String agentId;
    private String actionClass;
    private LocalDate day;
    private String statsJson;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getActionClass() { return actionClass; }
    public void setActionClass(String actionClass) { this.actionClass = actionClass; }
    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public String getStatsJson() { return statsJson; }
    public void setStatsJson(String statsJson) { this.statsJson = statsJson; }
}
