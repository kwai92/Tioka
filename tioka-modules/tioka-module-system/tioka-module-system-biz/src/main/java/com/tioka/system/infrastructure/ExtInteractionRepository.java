package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.ExtInteractionMapper;
import com.tioka.system.infrastructure.persistence.ExtInteractionQuery;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 外部交互台账写入口（OBS-04a / ADR-074）：台账只增，失败由调用方降级处理。 */
@Repository
public class ExtInteractionRepository {

    private final ExtInteractionMapper mapper;

    public ExtInteractionRepository(ExtInteractionMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(ExtInteractionWriteRow row) {
        mapper.insert(row);
    }

    /* ---- OBS-07 查询出口（ADR-074 §4） ---- */

    public List<Map<String, Object>> query(ExtInteractionQuery query) {
        return mapper.query(query);
    }

    public List<Map<String, Object>> destinationReport(String kind, String dataLabel,
                                                       Instant from, Instant to) {
        return mapper.destinationReport(kind, dataLabel, from, to);
    }

    public List<Map<String, Object>> counterpartyStats(String kind, Instant from, Instant to) {
        return mapper.counterpartyStats(kind, from, to);
    }
}
