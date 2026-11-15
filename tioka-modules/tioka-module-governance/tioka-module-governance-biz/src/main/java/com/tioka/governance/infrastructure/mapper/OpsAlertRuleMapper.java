package com.tioka.governance.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.governance.infrastructure.persistence.OpsAlertRuleDO;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** {@code ops_alert_rule} Mapper（GOV-13；自定义 SQL 在 mapper/OpsAlertRuleMapper.xml）。 */
public interface OpsAlertRuleMapper extends BaseMapper<OpsAlertRuleDO> {

    /** 参与求值的规则：enabled = TRUE，按创建时间升序。 */
    List<OpsAlertRuleDO> listEnabled();

    /** 全部规则（管理面列表，含停用）。 */
    List<OpsAlertRuleDO> listAll();

    /** 内置规则按名字幂等播种时查存在性（uk_alert_rule_builtin_name）。 */
    OpsAlertRuleDO findByBuiltinName(@Param("name") String name);

    /** 事件型条件推进游标水位。 */
    int touchLastFired(@Param("id") String id, @Param("at") LocalDateTime at);
}
