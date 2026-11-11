package com.tioka.server.knowledge;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.model.application.gateway.UsageRecorder;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KNW-01：真实嵌入走 ModelGateway、统一维度。见 M1-03 FLW-02/KNW-01。
 */
@SpringBootTest
@Tag("it")
class KnowledgeEmbeddingIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired KnowledgeService knowledge;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired UsageRecorder usageRecorder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void usesGateway() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            String bindingId = seedEmbeddingBinding(32);
            var kb = knowledge.createBase(ws, "kb-embed", bindingId);
            assertEquals(bindingId, kb.embeddingBindingId());

            var doc = knowledge.ingest(kb.id(), "doc", "hello gateway embedding", "upload");
            assertEquals("READY", doc.status());

            usageRecorder.flush();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT purpose, status, binding_id FROM model_usage WHERE workspace_id = ? AND purpose = 'EMBEDDING'",
                    ws);
            assertFalse(rows.isEmpty(), "ingest 须经 ModelGateway 记账 EMBEDDING");
            assertTrue(rows.stream().allMatch(r -> "SUCCESS".equals(r.get("status"))),
                    "EMBEDDING usage 应为 SUCCESS: " + rows);
            assertTrue(rows.stream().anyMatch(r -> bindingId.equals(r.get("binding_id"))),
                    "usage.binding_id 应为知识库绑定: " + rows);

            Integer dim = jdbc.queryForObject(
                    """
                    SELECT vector_dims(embedding_vec) FROM kb_chunk
                    WHERE kb_id = ? AND deleted = FALSE AND chunk_type = 'CHILD' LIMIT 1
                    """,
                    Integer.class, kb.id());
            assertEquals(32, dim);

            // C-15 起向量召回只见子块：父块不嵌入（embedding_vec 为 NULL）是设计语义
            Integer parentVecs = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM kb_chunk
                    WHERE kb_id = ? AND deleted = FALSE AND chunk_type = 'PARENT' AND embedding_vec IS NOT NULL
                    """,
                    Integer.class, kb.id());
            assertEquals(0, parentVecs, "父块不应携带嵌入向量");

            return null;
        });
    }

    @Test
    void rejectsDimMismatch() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            String wrong = seedEmbeddingBinding(64);
            BizException ex = assertThrows(BizException.class,
                    () -> knowledge.createBase(ws, "kb-bad-dim", wrong));
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("embedding-dim"), ex.getMessage());
            return null;
        });
    }

    @Test
    void rejectsNonEmbeddingKind() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            var provider = models.createProvider(new CreateProviderRequest(Ids.newId("p"), "mock://openai", "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "chat"));
            String chat = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
            BizException ex = assertThrows(BizException.class,
                    () -> knowledge.createBase(ws, "kb-chat", chat));
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
            return null;
        });
    }

    private String seedEmbeddingBinding(int dim) {
        var provider = models.createProvider(new CreateProviderRequest(Ids.newId("p"), "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "embed"));
        return models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "text-embedding", "embedding", dim));
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
    }
}
