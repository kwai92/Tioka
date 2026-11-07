package com.tioka.knowledge.application;

import com.tioka.framework.id.Ids;

import com.tioka.knowledge.api.WikiApi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.WikiPageDO;
import org.springframework.stereotype.Service;


@Service
public class WikiService implements WikiApi {
    private final KnowledgeService knowledgeService;
    private final KnowledgeRepository repo;

    public WikiService(KnowledgeService knowledgeService, KnowledgeRepository repo) {
        this.knowledgeService = knowledgeService;
        this.repo = repo;
    }

    @Override
    public WikiPageView upsert(String kbId, String title, String body) {
        String id = Ids.newId("wiki");
        WikiPageView page = new WikiPageView(id, kbId, title, body);
        WikiPageDO row = new WikiPageDO();
        row.setId(page.id());
        row.setKbId(page.kbId());
        row.setTitle(page.title());
        row.setBody(page.body());
        repo.insertWiki(row);
        knowledgeService.ingest(kbId, title, body, "wiki");
        return page;
    }
}
