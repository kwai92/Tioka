package com.tioka.knowledge.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.runtime.api.spi.ToolProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KbToolProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listsAndInvokesKbToolsBeyondEcho() throws Exception {
        KnowledgeRepository repo = KnowledgeInMemorySupport.repo();
        KnowledgeService knowledge = KnowledgeInMemorySupport.service(repo);
        var kb = knowledge.createBase("ws", "tools");
        var doc = knowledge.ingest(kb.id(), "doc", "citationId must appear in kb.search results for Tioka", "upload");

        KbToolProvider provider = new KbToolProvider(knowledge,
                (org.springframework.beans.factory.ObjectProvider<com.tioka.runtime.api.grant.GrantService>)
                        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                mapper);
        // ToolRegistry 聚合 ToolProvider；此处直接验证 SPI 契约（Echo 之外）
        List<String> names = provider.listTools("agent_v1").stream().map(ToolProvider.ToolDescriptor::name).toList();
        assertTrue(names.contains("kb.search"));
        assertTrue(names.contains("kb.read_chunk"));
        assertTrue(names.contains("kb.read_document"));
        assertEquals(3, names.size());

        ToolProvider.ToolResult search = provider.invoke("kb.search",
                Map.of("kbId", kb.id(), "query", "Tioka", "topK", 3));
        assertTrue(search.ok());
        List<Map<String, Object>> hits = mapper.readValue(search.content(), new TypeReference<>() {});
        assertTrue(hits != null && !hits.isEmpty());
        assertTrue(hits.get(0).containsKey("citationId"));
        assertTrue(hits.get(0).containsKey("chunkId"));
        assertTrue(hits.get(0).containsKey("text"));
        assertTrue(hits.get(0).containsKey("score"));

        String chunkId = String.valueOf(hits.get(0).get("chunkId"));
        ToolProvider.ToolResult chunk = provider.invoke("kb.read_chunk", Map.of("chunkId", chunkId));
        assertTrue(chunk.ok());
        assertTrue(chunk.content().contains(chunkId));

        ToolProvider.ToolResult document = provider.invoke("kb.read_document",
                Map.of("docId", doc.id(), "maxChunks", 2));
        assertTrue(document.ok());
        assertTrue(document.content().contains(doc.id()));
    }
}
