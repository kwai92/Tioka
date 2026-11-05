package com.tioka.knowledge.application;

import com.tioka.connector.api.spi.FeishuDocContentSpi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.system.api.spi.EgressGuard;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceServiceTest {

    @Test
    void bindWebAndSyncIngestsHttpBody() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("Hello from web datasource for Tioka sync"));
            server.start();
            String url = server.url("/page").toString();

            KnowledgeRepository repo = KnowledgeInMemorySupport.repo();
            KnowledgeService knowledge = KnowledgeInMemorySupport.service(repo);
            var kb = knowledge.createBase("ws", "ds");

            @SuppressWarnings("unchecked")
            ObjectProvider<EgressGuard> egress = mock(ObjectProvider.class);
            when(egress.getIfAvailable()).thenReturn(u -> {});

            @SuppressWarnings("unchecked")
            ObjectProvider<FeishuDocContentSpi> feishu = mock(ObjectProvider.class);
            when(feishu.getIfAvailable()).thenReturn(null);

            DataSourceService dsService = new DataSourceService(knowledge, repo, egress, feishu);

            var ds = dsService.bind(kb.id(), "web", url);
            assertNotNull(ds.id());
            String docId = dsService.sync(ds.id(), Map.of("title", "web-page"));
            assertNotNull(docId);
            assertFalse(docId.isBlank());

            var hits = knowledge.search(kb.id(), "Tioka sync", 3);
            assertTrue(hits.stream().anyMatch(h -> h.text().contains("web datasource")));
        }
    }
}
