package com.tioka.model.infrastructure;
import com.tioka.model.infrastructure.mapper.DecisionBindingMapper;
import com.tioka.model.infrastructure.persistence.DecisionBindingDO;
import org.springframework.stereotype.Repository;
@Repository
public class DecisionBindingRepository {
    private final DecisionBindingMapper mapper;
    public DecisionBindingRepository(DecisionBindingMapper mapper) { this.mapper = mapper; }
    public void insert(DecisionBindingDO row) { mapper.insert(row); }
}
