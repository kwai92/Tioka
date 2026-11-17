package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.RuntimeItemStatsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SCH-10（ADR-066 §10.1）：结构化输出指标聚合（仅 {@code type='data'} 的 {@code runtime_item}）。
 *
 * <p>按（schemaRef, 模型, 生成方式）分组，直接读 V232 / V234 的投影列，不解析 {@code content_json}。
 */
@Mapper
public interface RuntimeItemStatsMapper {

    /**
     * @param from 起始（含）；null 不限下界
     * @param to   结束（不含）；null 不限上界
     */
    List<RuntimeItemStatsRow> aggregateDataItems(@Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);
}
