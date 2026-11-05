package com.tioka.model.application;

import com.tioka.model.domain.DecisionBinding;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.DecisionBindingRepository;
import com.tioka.model.infrastructure.persistence.DecisionBindingDO;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DecisionModelService {
    private final DifficultyRouter difficultyRouter;
    private final ModelEntities entities;
    private final DecisionBindingRepository decisions;

    public DecisionModelService(DifficultyRouter difficultyRouter, ModelEntities entities,
                                DecisionBindingRepository decisions) {
        this.difficultyRouter = difficultyRouter;
        this.entities = entities;
        this.decisions = decisions;
    }

    public DecisionBinding bind(String bindingId) {
        if (!entities.hasBinding(bindingId)) {
            throw new IllegalArgumentException("binding 不存在");
        }
        DecisionBinding d = new DecisionBinding("dec_" + UUID.randomUUID().toString().substring(0, 8), bindingId, "DIFFICULTY");
        DecisionBindingDO row = new DecisionBindingDO();
        row.setId(d.id());
        row.setBindingId(d.bindingId());
        row.setPurpose(d.purpose());
        decisions.insert(row);
        return d;
    }

    public DifficultyRouter.Tier decideOnly(String text) {
        return difficultyRouter.decide(text);
    }
}
