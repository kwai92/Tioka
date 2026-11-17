package com.tioka.governance.infrastructure.config;

import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.persistence.CfgChangeDO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@code cfg_change} 只读仓储（OBS-08 / ADR-075 §2 时间线重建端口）。
 *
 * <p>application 只依赖本仓储，不直接持有 Mapper（PersistenceArchTest：application/kernel
 * 禁止直持 {@code ..infrastructure.mapper..}）。由 {@code TimelineAutoConfiguration} 装配。
 */
public class CfgChangeRepository {

    private final CfgChangeMapper mapper;

    public CfgChangeRepository(CfgChangeMapper mapper) {
        this.mapper = mapper;
    }

    /** 按落库时间区间读回配置变更（{@code null} 表示不限）。 */
    public List<CfgChangeDO> listByRange(Instant from, Instant to) {
        return mapper.listByRange(toUtc(from), toUtc(to));
    }

    private static LocalDateTime toUtc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
