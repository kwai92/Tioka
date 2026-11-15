package com.tioka.server.gov;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.governance.api.timeline.TimelineCategory;
import com.tioka.governance.api.timeline.TimelineEntryView;
import com.tioka.governance.api.timeline.TimelinePageSpec;
import com.tioka.governance.api.timeline.TimelineQuery;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.infrastructure.AuthUserRepository;
import com.tioka.system.infrastructure.persistence.AuthUserDO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-12 主体时间线端到端（ADR-075 §4 / M4-06 §7）：Agent / 知识文档 / 用户三类主体
 * 的来源事件经 {@link TimelineQuery#rebuild} 按时间段重建后，可在主体时间线查到
 * {@link TimelineCategory#CONFIG} 条目。
 *
 * <p>口径（真实数据库，无 mock）：
 * <ul>
 *   <li>{@code agent_version} → 主体 {@code agent:<agentId>}（空间左连 {@code agent_agent}）；</li>
 *   <li>{@code kb_document} → 主体 {@code knowledge_doc:<docId>}（空间左连 {@code kb_base}）；</li>
 *   <li>{@code auth_user} → 主体 {@code user:<userId>}（空间取 {@code default_workspace_id}）。</li>
 * </ul>
 *
 * <p>仅播种三张来源表（不经发布/入库业务链，避免牵入模型/嵌入装配）：重建源经
 * {@code Repository.listByRange} 读回行，完整覆盖「来源表 → 投影 → timeline_entry(+_subject) →
 * pageBySubject」链路。含 Testcontainers（PostgreSQL），本地无 Docker 时归 CI。
 */
@SpringBootTest
@Tag("it")
class M4Obs12SubjectTimelineIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentRepository agents;
    @Autowired KnowledgeRepository knowledge;
    @Autowired AuthUserRepository users;
    @Autowired TimelineQuery timeline;
    @Autowired PrincipalHolder principals;

    @Test
    void rebuildProjectsConfigEntriesForThreeSubjects() {
        String ws = Ids.newId("ws");
        Principal admin = admin("1", ws);
        Instant from = Instant.now().minus(Duration.ofMinutes(5));

        String[] ids = principals.runAs(admin, () -> {
            // —— 1. Agent 主体：agent_agent + agent_version ——
            String agentId = Ids.newId("agt");
            AgentDO agent = new AgentDO();
            agent.setId(agentId);
            agent.setName("obs12-agent");
            agent.setWorkspaceId(ws);
            agent.setEnabled(true);
            agent.setVersion(0);
            agents.upsertAgent(agent);

            AgentVersionDO version = new AgentVersionDO();
            version.setId(Ids.newId("av"));
            version.setAgentId(agentId);
            version.setVersionNo(1);
            version.setSpecJson("{}");
            version.setStatus("PUBLISHED");
            version.setNote("obs12 publish");
            version.setPublishedBy("1");
            version.setRoutingPlanId("plan-obs12");
            version.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
            agents.insertVersion(version);

            // —— 2. 知识文档主体：kb_base + kb_document ——
            String kbId = Ids.newId("kb");
            KbBaseDO base = new KbBaseDO();
            base.setId(kbId);
            base.setName("obs12-kb");
            base.setWorkspaceId(ws);
            knowledge.insertBase(base);

            String docId = Ids.newId("doc");
            KbDocumentDO doc = new KbDocumentDO();
            doc.setId(docId);
            doc.setKbId(kbId);
            doc.setTitle("obs12-doc");
            doc.setSourceType("upload");
            doc.setStatus("READY");
            doc.setFormat("md");
            doc.setParseStatus("READY");
            knowledge.insertDocument(doc);

            // —— 3. 用户主体：auth_user ——
            AuthUserDO account = new AuthUserDO();
            account.setId(users.nextId());
            account.setUsername("obs12-user-" + agentId);
            account.setDisplayName("OBS12 用户");
            account.setPasswordHash("x");
            account.setEnabled(true);
            account.setIdp("local");
            account.setType("USER");
            account.setDefaultWorkspaceId(ws);
            users.insert(account);

            return new String[] {agentId, docId, String.valueOf(account.getId())};
        });

        Instant to = Instant.now().plus(Duration.ofMinutes(5));
        int rebuilt = principals.runAs(admin, () -> timeline.rebuild(from, to));
        assertTrue(rebuilt > 0, "重建应至少投影三类主体的一条来源事件");

        TimelinePageSpec spec = new TimelinePageSpec(Set.of(), null, null, null, 200, null, null);
        assertHasConfig(principals.runAs(admin, () -> timeline.pageBySubject("agent", ids[0], spec)),
                "agent", ids[0]);
        assertHasConfig(principals.runAs(admin, () -> timeline.pageBySubject("knowledge_doc", ids[1], spec)),
                "knowledge_doc", ids[1]);
        assertHasConfig(principals.runAs(admin, () -> timeline.pageBySubject("user", ids[2], spec)),
                "user", ids[2]);
    }

    private static void assertHasConfig(List<TimelineEntryView> entries, String subjectType, String subjectId) {
        assertTrue(entries.stream().anyMatch(e -> e.category() == TimelineCategory.CONFIG),
                () -> subjectType + ":" + subjectId + " 主体时间线应含 CONFIG 条目，实际类别="
                        + entries.stream().map(TimelineEntryView::category).toList());
    }

    private static Principal admin(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("system_admin"), Set.of("*"), "it");
    }
}
