package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.RuntimeItemStatsMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemStatsRow;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * runtime_item 指标聚合仓储（SCH-10，ADR-066 §10.1）。
 *
 * <p>application 层经本类访问，<b>禁止直持 Mapper</b>——与 {@code GovPolicyRepository}
 * 同构，由 {@code PersistenceArchTest#applicationAndKernelMustNotDependOnMappers} 守门。
 *
 * <p>本类只做转发，不承担聚合语义：分组口径、投影列与时间窗口边界全部由
 * {@code mapper/RuntimeItemStatsMapper.xml} 定义，避免同一份 SQL 语义散落两处。
 */
@Repository
public class RuntimeItemStatsRepository {

    private final RuntimeItemStatsMapper mapper;

    public RuntimeItemStatsRepository(RuntimeItemStatsMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 聚合 {@code type='data'} 的 runtime_item 指标。
     *
     * @param from 起始（含，UTC）；{@code null} 不限下界
     * @param to   结束（不含，UTC）；{@code null} 不限上界
     */
    public List<RuntimeItemStatsRow> aggregateDataItems(LocalDateTime from, LocalDateTime to) {
        return mapper.aggregateDataItems(from, to);
    }
}
