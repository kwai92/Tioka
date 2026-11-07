package com.tioka.server.knowledge;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.model.api.dto.ProviderDtos.CreateBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.auth.PrincipalLoader;
import com.tioka.system.infrastructure.SysResourceAclRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KNW-02：知识库 ACL 下推检索。见 M1-04。
 */
@SpringBootTest
@Tag("it")
class KnowledgeAclIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired KnowledgeService knowledge;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @Autowired PrincipalLoader principalLoader;
    @Autowired SysResourceAclRepository acls;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @Test
    void restrictedDocInvisible() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        String[] ids = principals.runAs(adminPrincipal(admin), () -> {
            String embed = seedEmbeddingBinding();
            var allowed = knowledge.createBase(admin.workspaceId(), "kb-vis", embed);
            var denied = knowledge.createBase(admin.workspaceId(), "kb-hid", embed);
            jdbc.update("UPDATE kb_base SET visibility = 'PRIVATE' WHERE id IN (?, ?)",
                    allowed.id(), denied.id());
            acls.upsert("kb", allowed.id(), "user", member.userId(), "USE");
            knowledge.ingest(allowed.id(), "a", "visible-token-acl-aaa unique-policy", "upload");
            knowledge.ingest(denied.id(), "b", "hidden-token-acl-bbb unique-policy", "upload");
            return new String[]{allowed.id(), denied.id()};
        });

        Principal memberP = loaded(member);
        List<KnowledgeService.RetrievalHit> hits = principals.runAs(memberP, () ->
                knowledge.retrieve(List.of(ids[0], ids[1]), "unique-policy", 10));

        String joined = hits.stream().map(KnowledgeService.RetrievalHit::text).collect(Collectors.joining("\n"));
        assertTrue(joined.contains("visible-token-acl-aaa"), "应命中有权知识库: " + joined);
        assertFalse(joined.contains("hidden-token-acl-bbb"), "无权文档不得出现: " + joined);
        assertTrue(hits.stream().allMatch(h -> ids[0].equals(h.kbId())), "命中须全部来自有权 KB");
    }

    @Test
    void topKNotStarvedByInvisible() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor member = fixtures.newLimitedUser(admin, "member");

        String query = "topk-starvation-query-exact-match-xyz";
        String[] ids = principals.runAs(adminPrincipal(admin), () -> {
            String embed = seedEmbeddingBinding();
            var allowed = knowledge.createBase(admin.workspaceId(), "kb-topk-a", embed);
            var denied = knowledge.createBase(admin.workspaceId(), "kb-topk-d", embed);
            jdbc.update("UPDATE kb_base SET visibility = 'PRIVATE' WHERE id IN (?, ?)",
                    allowed.id(), denied.id());
            acls.upsert("kb", allowed.id(), "user", member.userId(), "USE");
            // 无权库：与 query 完全一致 → 向量相似度更高
            knowledge.ingest(denied.id(), "hot", query, "upload");
            // 有权库：仅部分重叠 → 相似度更低，若召回后再过滤会被 topK=1 饿死
            knowledge.ingest(allowed.id(), "cold", "topk-starvation other words", "upload");
            return new String[]{allowed.id(), denied.id()};
        });

        Principal memberP = loaded(member);
        List<KnowledgeService.RetrievalHit> hits = principals.runAs(memberP, () ->
                knowledge.retrieve(List.of(ids[0], ids[1]), query, 1));

        assertEquals(1, hits.size(), "ACL 下推后 topK=1 仍应返回有权文档");
        assertEquals(ids[0], hits.get(0).kbId());
        assertFalse(hits.get(0).text().contains(query) && hits.get(0).text().equals(query)
                        && ids[1].equals(hits.get(0).kbId()),
                "不得返回无权高相似文档");
        assertTrue(hits.get(0).text().contains("topk-starvation"), hits.get(0).text());
    }

    @Test
    void crossWorkspaceInvisible() {
        // newActor 挂在 bootstrap admin（含 *）上；跨空间验收须用无 * 的受限成员
        TestFixtures.Actor adminA = fixtures.newActor("workspace_admin");
        TestFixtures.Actor memberA = fixtures.newLimitedUser(adminA, "member");
        TestFixtures.Actor adminB = fixtures.newActor("workspace_admin");

        String kbB = principals.runAs(adminPrincipal(adminB), () -> {
            String embed = seedEmbeddingBinding();
            var kb = knowledge.createBase(adminB.workspaceId(), "kb-ws-b", embed);
            knowledge.ingest(kb.id(), "secret", "cross-ws-secret-token-zzz", "upload");
            return kb.id();
        });

        Principal pa = loaded(memberA);
        List<KnowledgeService.RetrievalHit> hits = principals.runAs(pa, () ->
                knowledge.retrieve(List.of(kbB), "cross-ws-secret-token-zzz", 5));
        assertTrue(hits.isEmpty(), "跨空间知识库检索须为空: " + hits);
    }

    private String seedEmbeddingBinding() {
        String id = Ids.newId("emb");
        var provider = models.createProvider(new CreateProviderRequest(id + "-p", "mock://openai", "k"));
        String lm = models.createLogicalModel(new CreateLogicalModelRequest(id + "-m", "embed"));
        return models.createBinding(new CreateBindingRequest(lm, provider.id(), "text-embedding", "embedding", 32));
    }

    private Principal adminPrincipal(TestFixtures.Actor admin) {
        return new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), Set.of("*"), "password");
    }

    private Principal loaded(TestFixtures.Actor actor) {
        return principalLoader.load(Long.parseLong(actor.userId()), actor.workspaceId());
    }
}
