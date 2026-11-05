package com.tioka.runtime.infrastructure;

import com.tioka.runtime.infrastructure.mapper.AutomationJobMapper;
import com.tioka.runtime.infrastructure.persistence.AutomationJobDO;
import org.springframework.stereotype.Repository;

@Repository
public class AutomationJobRepository {
    private final AutomationJobMapper mapper;
    public AutomationJobRepository(AutomationJobMapper mapper) { this.mapper = mapper; }
    public void insert(AutomationJobDO row) { mapper.insert(row); }
}
