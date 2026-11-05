package com.tioka.knowledge.application;

import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbWikiDO;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WikiService {
    private final KnowledgeService knowledgeService;
    private final KnowledgeRepository repo;

    public WikiService(KnowledgeService knowledgeService, KnowledgeRepository repo) {
        this.knowledgeService = knowledgeService;
        this.repo = repo;
    }

    public WikiPage upsert(String kbId, String title, String body) {
        String id = "wiki_" + UUID.randomUUID().toString().substring(0, 8);
        WikiPage page = new WikiPage(id, kbId, title, body);
        KbWikiDO row = new KbWikiDO();
        row.setId(page.id());
        row.setKbId(page.kbId());
        row.setTitle(page.title());
        row.setBody(page.body());
        repo.insertWiki(row);
        knowledgeService.ingest(kbId, title, body, "wiki");
        return page;
    }

    public record WikiPage(String id, String kbId, String title, String body) {}
}
