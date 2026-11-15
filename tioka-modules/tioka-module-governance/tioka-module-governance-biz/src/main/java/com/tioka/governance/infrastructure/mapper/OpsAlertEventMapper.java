package com.tioka.governance.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.governance.infrastructure.persistence.OpsAlertEventDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** {@code ops_alert_event} Mapper（GOV-13；自定义 SQL 在 mapper/OpsAlertEventMapper.xml）。 */
public interface OpsAlertEventMapper extends BaseMapper<OpsAlertEventDO> {

    /**
     * 取同一规则、同一对象、同一条件的最新事件，用于状态去重与恢复判定。
     *
     * <p>{@code target_id} 可能为空，比较用 {@code IS NOT DISTINCT FROM}。
     */
    OpsAlertEventDO findLatest(@Param("ruleId") String ruleId,
                               @Param("targetId") String targetId,
                               @Param("conditionType") String conditionType);

    /** 事件历史查询（{@code null} 表示不限；按 observed_at 倒序取前 {@code limit} 条）。 */
    List<OpsAlertEventDO> listRecent(@Param("ruleId") String ruleId,
                                     @Param("targetId") String targetId,
                                     @Param("state") String state,
                                     @Param("limit") int limit);

    /** 通知成功后置位（避免重复外发）。 */
    int markNotified(@Param("id") String id);
}
