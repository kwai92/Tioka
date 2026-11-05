package com.tioka.infra.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.infra.infrastructure.mapper.TraceSpanMapper;
import com.tioka.infra.infrastructure.persistence.TraceSpanDO;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class TraceSpanRepository {
    private final TraceSpanMapper mapper;
    public TraceSpanRepository(TraceSpanMapper mapper) { this.mapper = mapper; }
    public void insert(TraceSpanDO row) { mapper.insert(row); }
    public List<TraceSpanDO> findByTraceId(String traceId) {
        return mapper.selectList(Wrappers.<TraceSpanDO>lambdaQuery()
                .eq(TraceSpanDO::getTraceId, traceId).orderByAsc(TraceSpanDO::getId));
    }
}
