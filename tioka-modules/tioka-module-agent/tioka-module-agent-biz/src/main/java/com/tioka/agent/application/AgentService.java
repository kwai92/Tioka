package com.tioka.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.AgentPublishedEvent;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.publish.PublishValidator;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.AgentSpecJson;
import com.tioka.agent.domain.AgentStore;
import com.tioka.agent.domain.AgentStore.Agent;
import com.tioka.agent.infrastructure.AgentDraftRepository;
import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDraftDO;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.json.CanonicalJson;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.system.api.audit.Audited;
import com.tioka.system.api.authz.ResourceAclApi;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentService {
    private final AgentStore store;
    private final AgentRepository repo;
    private final AgentDraftRepository drafts;
    private final ResourceGuard resourceGuard;
    private final ResourceAclApi resourceAcl;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    private final PublishValidator publishValidator;
    private final ApplicationEventPublisher events;

    public AgentService(AgentStore store,
                        AgentRepository repo,
                        AgentDraftRepository drafts,
                        ResourceGuard resourceGuard,
                        ResourceAclApi resourceAcl,
                        PrincipalHolder principals,
                        ObjectMapper mapper,
                        PublishValidator publishValidator,
                        ApplicationEventPublisher events) {
        this.store = store;
        this.repo = repo;
        this.drafts = drafts;
        this.resourceGuard = resourceGuard;
        this.resourceAcl = resourceAcl;
        this.principals = principals;
        this.mapper = mapper;
        this.publishValidator = publishValidator;
        this.events = events;
    }

    public Agent create(String name, String workspaceId, String visibility) {
        String id = Ids.newId("agt");
        String ws = workspaceId == null || workspaceId.isBlank()
                ? principals.current().workspaceId() : workspaceId;
        String vis = visibility == null || visibility.isBlank() ? "WORKSPACE" : visibility;
        String owner = principals.current().userId();
        Agent a = new Agent(id, name, ws, false, null, vis, owner);
        store.putAgent(a);
        resourceAcl.ensureOwner("agent", id, owner);
        AgentSpec initial = defaultSpec(name);
        ensureDraft(id, initial, null);
        return a;
    }

    public Agent create(String name, String workspaceId) {
        return create(name, workspaceId, "WORKSPACE");
    }

    public Agent get(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.VIEW);
        return store.getAgent(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
    }

    public Agent update(String agentId, String name, String visibility) {
        resourceGuard.check("agent", agentId, AccessLevel.EDIT);
        Agent existing = store.getAgent(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
        Agent updated = new Agent(
                existing.id(),
                name == null || name.isBlank() ? existing.name() : name,
                existing.workspaceId(),
                existing.enabled(),
                existing.publishedVersionId(),
                visibility == null || visibility.isBlank() ? existing.visibility() : visibility,
                existing.ownerId());
        store.putAgent(updated);
        return updated;
    }

    public DraftView getDraft(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.VIEW);
        AgentDraftDO row = drafts.find(agentId);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
        }
        return new DraftView(AgentSpecJson.parse(mapper, row.getSpecJson()),
                row.getRevision() == null ? 0 : row.getRevision(),
                row.getBaseVersionId());
    }

    public DraftView saveDraft(String agentId, AgentSpec spec, int revision) {
        resourceGuard.check("agent", agentId, AccessLevel.EDIT);
        if (store.getAgent(agentId).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "agent 不存在");
        }
        AgentSpecJson.requireSchema(mapper, spec);
        AgentDraftDO row = drafts.find(agentId);
        if (row == null) {
            ensureDraft(agentId, spec, null);
            row = drafts.find(agentId);
            return new DraftView(spec, row.getRevision(), row.getBaseVersionId());
        }
        if (row.getRevision() == null || row.getRevision() != revision) {
            throw new BizException(ErrorCode.STALE_REVISION, "草稿 revision 冲突");
        }
        row.setSpecJson(AgentSpecJson.write(mapper, spec));
        row.setSchemaVersion(spec.schemaVersion());
        row.setUpdateAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdateBy(principals.current().userId());
        drafts.update(row);
        AgentDraftDO fresh = drafts.find(agentId);
        return new DraftView(spec,
                fresh.getRevision() == null ? revision + 1 : fresh.getRevision(),
                fresh.getBaseVersionId());
    }

    public ValidateResult validate(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.EDIT);
        DraftView draft = getDraft(agentId);
        List<PublishCheck.PublishError> errors = publishValidator.validate(
                draft.spec(),
                new PublishCheck.PublishContext(agentId, principals.current().userId(), false));
        return new ValidateResult(errors.isEmpty(), errors);
    }

    @Transactional
    @Audited(action = "agent.publish", resourceType = "agent", resourceIdArg = "#agentId")
    public PublishResult publish(String agentId, int revision, String note) {
        resourceGuard.check("agent", agentId, AccessLevel.EDIT);
        Agent agent = store.getAgent(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
        AgentDraftDO draft = drafts.find(agentId);
        if (draft == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
        }
        if (draft.getRevision() == null || draft.getRevision() != revision) {
            throw new BizException(ErrorCode.STALE_REVISION, "草稿 revision 冲突");
        }
        AgentSpec spec = AgentSpecJson.parse(mapper, draft.getSpecJson());
        AgentSpecJson.requireSchema(mapper, spec);
        List<PublishCheck.PublishError> errors = publishValidator.validate(
                spec, new PublishCheck.PublishContext(agentId, principals.current().userId(), false));
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_PUBLISH_INVALID, "Agent 发布校验失败",
                    Map.of("errors", errors));
        }
        int nextNo = repo.maxVersionNo(agentId) + 1;
        String versionId = Ids.newId("av");
        String hash = CanonicalJson.sha256Hex(mapper, spec);
        String specJson = AgentSpecJson.write(mapper, spec);
        AgentVersionDO ver = new AgentVersionDO();
        ver.setId(versionId);
        ver.setAgentId(agentId);
        ver.setVersionNo(nextNo);
        ver.setSpecJson(specJson);
        ver.setSchemaVersion(spec.schemaVersion());
        ver.setStatus("PUBLISHED");
        ver.setNote(note);
        ver.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        ver.setPublishedBy(principals.current().userId());
        ver.setRoutingPlanId(spec.model().routingPlanId());
        ver.setSnapshotHash(hash);
        repo.insertVersion(ver);

        store.putAgent(new Agent(agent.id(), agent.name(), agent.workspaceId(), true,
                versionId, agent.visibility(), agent.ownerId()));
        draft.setBaseVersionId(versionId);
        draft.setUpdateAt(LocalDateTime.now(ZoneOffset.UTC));
        draft.setUpdateBy(principals.current().userId());
        drafts.update(draft);
        publishAfterCommit(agentId, versionId);
        return new PublishResult(versionId, nextNo);
    }

    public List<VersionView> listVersions(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.VIEW);
        Agent agent = get(agentId);
        List<VersionView> out = new ArrayList<>();
        for (AgentVersionDO v : repo.listVersions(agentId)) {
            out.add(toVersionView(v, agent.publishedVersionId()));
        }
        return out;
    }

    public VersionView getVersion(String agentId, String versionId) {
        resourceGuard.check("agent", agentId, AccessLevel.VIEW);
        Agent agent = get(agentId);
        AgentVersionDO v = repo.findVersion(versionId);
        if (v == null || !agentId.equals(v.getAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "version 不存在");
        }
        return toVersionView(v, agent.publishedVersionId());
    }

    public List<Map<String, Object>> diff(String agentId, String fromId, String toId) {
        resourceGuard.check("agent", agentId, AccessLevel.VIEW);
        AgentVersionDO from = requireOwnedVersion(agentId, fromId);
        AgentVersionDO to = requireOwnedVersion(agentId, toId);
        try {
            JsonNode a = mapper.readTree(from.getSpecJson());
            JsonNode b = mapper.readTree(to.getSpecJson());
            return jsonPatch(a, b, "");
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "diff 失败: " + e.getMessage());
        }
    }

    @Transactional
    @Audited(action = "agent.rollback", resourceType = "agent", resourceIdArg = "#agentId")
    public PublishResult rollback(String agentId, String versionId) {
        resourceGuard.check("agent", agentId, AccessLevel.EDIT);
        Agent agent = store.getAgent(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
        AgentVersionDO target = requireOwnedVersion(agentId, versionId);
        if (!"PUBLISHED".equals(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能回滚到 PUBLISHED 版本");
        }
        AgentSpec spec = AgentSpecJson.parse(mapper, target.getSpecJson());
        List<PublishCheck.PublishError> errors = publishValidator.validate(
                spec, new PublishCheck.PublishContext(agentId, principals.current().userId(), true));
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_PUBLISH_INVALID, "回滚校验失败",
                    Map.of("errors", errors));
        }
        store.putAgent(new Agent(agent.id(), agent.name(), agent.workspaceId(), true,
                target.getId(), agent.visibility(), agent.ownerId()));
        AgentDraftDO draft = drafts.find(agentId);
        if (draft != null) {
            draft.setSpecJson(target.getSpecJson());
            draft.setBaseVersionId(target.getId());
            draft.setUpdateAt(LocalDateTime.now(ZoneOffset.UTC));
            draft.setUpdateBy(principals.current().userId());
            drafts.update(draft);
        }
        publishAfterCommit(agentId, target.getId());
        return new PublishResult(target.getId(), target.getVersionNo());
    }

    @Transactional
    @Audited(action = "agent.disable", resourceType = "agent", resourceIdArg = "#agentId")
    public Agent disable(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.OWNER);
        Agent agent = get(agentId);
        Agent updated = new Agent(agent.id(), agent.name(), agent.workspaceId(), false,
                agent.publishedVersionId(), agent.visibility(), agent.ownerId());
        store.putAgent(updated);
        return updated;
    }

    @Transactional
    @Audited(action = "agent.enable", resourceType = "agent", resourceIdArg = "#agentId")
    public Agent enable(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.OWNER);
        Agent agent = get(agentId);
        Agent updated = new Agent(agent.id(), agent.name(), agent.workspaceId(), true,
                agent.publishedVersionId(), agent.visibility(), agent.ownerId());
        store.putAgent(updated);
        return updated;
    }

    public List<Agent> list() {
        Set<String> visible = resourceGuard.visibleIds("agent", AccessLevel.VIEW);
        return store.agents().stream().filter(a -> visible.contains(a.id())).toList();
    }

    public void requireUse(String agentId) {
        resourceGuard.check("agent", agentId, AccessLevel.USE);
        Agent a = store.getAgent(agentId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "agent 不存在"));
        if (!a.enabled()) {
            throw new BizException(ErrorCode.AGENT_DISABLED, "Agent 已停用");
        }
    }

    /** 测试/闸门播种：直接写入草稿并发布（绕过 HTTP）。 */
    @Transactional
    public PublishResult publishSpec(String agentId, AgentSpec spec) {
        resourceGuard.check("agent", agentId, AccessLevel.EDIT);
        DraftView draft = saveDraft(agentId, spec, getDraft(agentId).revision());
        return publish(agentId, draft.revision(), null);
    }

    private void ensureDraft(String agentId, AgentSpec spec, String baseVersionId) {
        if (drafts.find(agentId) != null) return;
        AgentDraftDO row = new AgentDraftDO();
        row.setAgentId(agentId);
        row.setSpecJson(AgentSpecJson.write(mapper, spec));
        row.setSchemaVersion(spec.schemaVersion());
        row.setRevision(0);
        row.setBaseVersionId(baseVersionId);
        row.setUpdateAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdateBy(principals.current().userId());
        drafts.insert(row);
    }

    private AgentSpec defaultSpec(String name) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile(name, null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", ""))),
                new AgentSpec.ModelPolicy("unset", false),
                AgentSpec.ToolSet.empty(),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null);
    }

    private AgentVersionDO requireOwnedVersion(String agentId, String versionId) {
        AgentVersionDO v = repo.findVersion(versionId);
        if (v == null || !agentId.equals(v.getAgentId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "version 不存在");
        }
        return v;
    }

    private VersionView toVersionView(AgentVersionDO v, String currentId) {
        return new VersionView(
                v.getId(),
                v.getVersionNo() == null ? 0 : v.getVersionNo(),
                v.getStatus(),
                v.getPublishedAt() == null ? null : v.getPublishedAt().toInstant(ZoneOffset.UTC),
                v.getPublishedBy(),
                v.getNote(),
                v.getId().equals(currentId),
                AgentSpecJson.parse(mapper, v.getSpecJson()),
                v.getSnapshotHash());
    }

    private void publishAfterCommit(String agentId, String versionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publishEvent(new AgentPublishedEvent(agentId, versionId));
                }
            });
        } else {
            events.publishEvent(new AgentPublishedEvent(agentId, versionId));
        }
    }

    /** 极简 RFC 6902：对象键级 replace/add/remove。 */
    private List<Map<String, Object>> jsonPatch(JsonNode from, JsonNode to, String path) {
        List<Map<String, Object>> ops = new ArrayList<>();
        if (from.equals(to)) {
            return ops;
        }
        if (!from.isObject() || !to.isObject()) {
            ops.add(Map.of("op", "replace", "path", path.isEmpty() ? "/" : path, "value", to));
            return ops;
        }
        ObjectNode a = (ObjectNode) from;
        ObjectNode b = (ObjectNode) to;
        Iterator<String> aNames = a.fieldNames();
        while (aNames.hasNext()) {
            String k = aNames.next();
            String p = path + "/" + escapePointer(k);
            if (!b.has(k)) {
                ops.add(Map.of("op", "remove", "path", p));
            } else if (!a.get(k).equals(b.get(k))) {
                ops.addAll(jsonPatch(a.get(k), b.get(k), p));
            }
        }
        Iterator<String> bNames = b.fieldNames();
        while (bNames.hasNext()) {
            String k = bNames.next();
            if (!a.has(k)) {
                String p = path + "/" + escapePointer(k);
                ops.add(Map.of("op", "add", "path", p, "value", b.get(k)));
            }
        }
        return ops;
    }

    private static String escapePointer(String s) {
        return s.replace("~", "~0").replace("/", "~1");
    }

    public record DraftView(AgentSpec spec, int revision, String baseVersionId) {}

    public record PublishResult(String versionId, int versionNo) {}

    public record ValidateResult(boolean ok, List<PublishCheck.PublishError> errors) {}

    public record VersionView(String versionId, int versionNo, String status,
                              java.time.Instant publishedAt, String publishedBy, String note,
                              boolean current, AgentSpec spec, String contentHash) {}
}
