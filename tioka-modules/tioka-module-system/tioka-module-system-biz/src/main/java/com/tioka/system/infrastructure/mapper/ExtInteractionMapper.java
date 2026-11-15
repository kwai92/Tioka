package com.tioka.system.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.system.infrastructure.persistence.ExtInteractionQuery;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 外部交互台账（ADR-074）：写入只增不改；查询出口为 OBS-07（§4 查询与统计）。
 * 台账无租户列，忽略 ScopeLine，避免查询端无 Principal 时被 deny-by-default 拦住。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ExtInteractionMapper {

    int insert(ExtInteractionWriteRow row);

    /** 明细查询：按关联键 / 对方 / 回执号 / 类别 / 方向 / 时间段，时间倒序。 */
    List<Map<String, Object>> query(ExtInteractionQuery q);

    /** 数据去向报表：只统计出站，按 {@code data_label × kind × counterparty} 聚合。 */
    List<Map<String, Object>> destinationReport(@Param("kind") String kind,
                                                @Param("dataLabel") String dataLabel,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** 调用统计：按 {@code kind × counterparty} 聚合调用量、失败数、P95 时延。 */
    List<Map<String, Object>> counterpartyStats(@Param("kind") String kind,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);
}
