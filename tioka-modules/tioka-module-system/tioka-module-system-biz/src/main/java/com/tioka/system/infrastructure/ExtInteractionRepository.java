package com.tioka.system.infrastructure;

import com.tioka.system.infrastructure.mapper.ExtInteractionMapper;
import com.tioka.system.infrastructure.persistence.ExtInteractionWriteRow;
import org.springframework.stereotype.Repository;

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
}
