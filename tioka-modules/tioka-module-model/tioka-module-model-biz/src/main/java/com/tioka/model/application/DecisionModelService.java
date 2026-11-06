package com.tioka.model.application;

import com.tioka.framework.id.Ids;

import com.tioka.model.domain.DecisionBinding;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.DecisionBindingRepository;
import com.tioka.model.infrastructure.persistence.DecisionBindingDO;
import org.springframework.stereotype.Service;


@Service
public class DecisionModelService {
    private final ModelEntities entities;
    private final DecisionBindingRepository decisions;

    public DecisionModelService(ModelEntities entities, DecisionBindingRepository decisions) {
        this.entities = entities;
        this.decisions = decisions;
    }

    public DecisionBinding bind(String bindingId) {
        if (!entities.hasBinding(bindingId)) {
            throw new IllegalArgumentException("binding 不存在");
        }
        DecisionBinding d = new DecisionBinding(Ids.newId("dec"), bindingId, "DIFFICULTY");
        DecisionBindingDO row = new DecisionBindingDO();
        row.setId(d.id());
        row.setBindingId(d.bindingId());
        row.setPurpose(d.purpose());
        decisions.insert(row);
        return d;
    }
}
