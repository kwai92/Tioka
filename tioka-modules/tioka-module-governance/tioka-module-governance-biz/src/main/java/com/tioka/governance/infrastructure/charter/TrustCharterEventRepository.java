package com.tioka.governance.infrastructure.charter;

import com.tioka.governance.infrastructure.mapper.TrustCharterEventMapper;
import com.tioka.governance.infrastructure.persistence.TrustCharterEventDO;

/** 由 CharterAutoConfiguration 装配，避免特性关闭时被全局 ComponentScan 拉起。 */
public class TrustCharterEventRepository {

    private final TrustCharterEventMapper mapper;

    public TrustCharterEventRepository(TrustCharterEventMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(TrustCharterEventDO row) {
        mapper.insert(row);
    }
}
