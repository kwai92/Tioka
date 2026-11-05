package com.tioka.runtime.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.runtime.infrastructure.mapper.CostEventMapper;
import com.tioka.runtime.infrastructure.persistence.CostEventDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CostEventRepository {
    private final CostEventMapper mapper;
    public CostEventRepository(CostEventMapper mapper) { this.mapper = mapper; }
    public void insert(CostEventDO row) { mapper.insert(row); }
    public List<CostEventDO> findBySessionId(String sessionId) {
        return mapper.selectList(Wrappers.<CostEventDO>lambdaQuery()
                .eq(CostEventDO::getSessionId, sessionId).orderByAsc(CostEventDO::getId));
    }
}
