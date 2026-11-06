package com.tioka.server.gov;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.embedding.EmbeddingClient;
import com.tioka.knowledge.infrastructure.mapper.KbDocumentMapper;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Tag("it")
class KnowledgeIngestTxIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository repo;
    @Autowired KbDocumentMapper documents;
    @Autowired PrincipalHolder principals;
    @MockBean EmbeddingClient embeddingClient;

    @Test
    void embeddingFailureMarksFailed() {
        String ws = Ids.newId("ws");
        when(embeddingClient.embed(anyString())).thenThrow(new RuntimeException("embed-down"));

        principals.runAs(user("1", ws), () -> {
            var kb = knowledge.createBase(ws, "kb-tx");
            assertThrows(Exception.class, () ->
                    knowledge.ingest(kb.id(), "doc", "hello world content", "upload"));

            List<KbDocumentDO> docs = documents.selectList(
                    Wrappers.<KbDocumentDO>lambdaQuery().eq(KbDocumentDO::getKbId, kb.id()));
            assertEquals(1, docs.size());
            KbDocumentDO doc = docs.get(0);
            assertEquals("FAILED", doc.getStatus());
            assertNotNull(doc.getErrorMessage());
            assertTrue(doc.getErrorMessage().contains("embed-down"));
            assertTrue(repo.listChunksByDoc(doc.getId()).isEmpty());
        });
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
    }
}
