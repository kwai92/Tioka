package com.tioka.knowledge.application;

import com.tioka.knowledge.api.KnowledgeQueryApi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeQueryService implements KnowledgeQueryApi {
    private final KnowledgeRepository repo;

    public KnowledgeQueryService(KnowledgeRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean lockExists(String kbId) {
        if (kbId == null || kbId.isBlank()) {
            return false;
        }
        return repo.lockBaseExists(kbId);
    }
}
