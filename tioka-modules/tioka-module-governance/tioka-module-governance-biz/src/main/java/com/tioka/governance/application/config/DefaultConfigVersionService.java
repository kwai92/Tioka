package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.governance.infrastructure.config.CfgStore;
import com.tioka.governance.infrastructure.persistence.CfgDraftDO;
import com.tioka.governance.infrastructure.persistence.CfgVersionDO;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GOV-12 配置版本框架实现（ADR-041 §2）。
 */
public class DefaultConfigVersionService implements ConfigVersionApi {

    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String EXPORT_FORMAT = "1";

    private final CfgStore store;
    private final ConfigSpecNormalizer normalizer;
    private final ConfigProjectionCache cache;
    private final ConfigCurrentPointerStore pointers;
    private final ConfigVersionRefreshBus bus;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<AuditApi> auditApi;
    private final String actorId;

    public DefaultConfigVersionService(
            CfgStore store,
            ConfigSpecNormalizer normalizer,
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            ConfigVersionRefreshBus bus,
            ObjectMapper objectMapper,
            ObjectProvider<AuditApi> auditApi) {
        this(store, normalizer, cache, pointers, bus, objectMapper, auditApi, "system");
    }

    public DefaultConfigVersionService(
            CfgStore store,
            ConfigSpecNormalizer normalizer,
            ConfigProjectionCache cache,
            ConfigCurrentPointerStore pointers,
            ConfigVersionRefreshBus bus,
            ObjectMapper objectMapper,
            ObjectProvider<AuditApi> auditApi,
            String actorId) {
        this.store = store;
        this.normalizer = normalizer;
        this.cache = cache;
        this.pointers = pointers;
        this.bus = bus;
        this.objectMapper = objectMapper;
        this.auditApi = auditApi;
        this.actorId = actorId == null ? "system" : actorId;
    }

    @Override
    @Transactional
    public ConfigDraft saveDraft(SaveDraftCommand command) {
        ObjectRef ref = requireRef(command.ref());
        if (command.spec() == null || command.spec().isNull()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "spec 不能为空");
        }
        JsonNode normalized = normalizer.stripAndNormalize(command.spec());
        String json = normalizer.toCanonicalJson(normalized);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Optional<CfgDraftDO> existing = store.findDraft(ref.objectType(), ref.objectId());
        if (existing.isEmpty()) {
            if (command.expectedRevision() != 0L) {
                throw new BizException(ErrorCode.STALE_REVISION, "草稿 revision 冲突");
            }
            CfgDraftDO row = new CfgDraftDO();
            row.setObjectType(ref.objectType());
            row.setObjectId(ref.objectId());
            row.setSpec(json);
            row.setRevision(1L);
            row.setBasedOnVersion(currentVersionNo(ref).map(Integer::longValue).orElse(null));
            row.setUpdateAt(now);
            row.setUpdateBy(actorId);
            store.insertDraft(row);
            return new ConfigDraft(ref, normalized, row.getRevision(),
                    row.getBasedOnVersion() == null ? null : row.getBasedOnVersion().intValue());
        }
        CfgDraftDO row = existing.get();
        if (row.getRevision() == null || row.getRevision() != command.expectedRevision()) {
            throw new BizException(ErrorCode.STALE_REVISION, "草稿 revision 冲突");
        }
        long next = row.getRevision() + 1;
        row.setSpec(json);
        row.setRevision(next);
        row.setUpdateAt(now);
        row.setUpdateBy(actorId);
        if (!store.updateDraftOptimistic(row, command.expectedRevision())) {
            throw new BizException(ErrorCode.STALE_REVISION, "草稿 revision 冲突");
        }
        return new ConfigDraft(ref, normalized, next,
                row.getBasedOnVersion() == null ? null : row.getBasedOnVersion().intValue());
    }

    @Override
    public ValidationReport validate(ObjectRef ref) {
        ObjectRef r = requireRef(ref);
        Optional<CfgDraftDO> draft = store.findDraft(r.objectType(), r.objectId());
        List<ValidationIssue> issues = new ArrayList<>();
        if (draft.isEmpty()) {
            issues.add(new ValidationIssue("DRAFT_NOT_FOUND", "ERROR", "草稿不存在", null));
            return new ValidationReport(false, List.copyOf(issues));
        }
        JsonNode spec = normalizer.readTree(draft.get().getSpec());
        if (!spec.isObject() && !spec.isArray()) {
            issues.add(new ValidationIssue("SPEC_TYPE", "ERROR", "spec 必须是对象或数组", "/"));
        }
        // 类型专属检查由 MH-08 / AST-07 注册；框架只做基线
        boolean ok = issues.stream().noneMatch(i -> "ERROR".equals(i.severity()));
        return new ValidationReport(ok, List.copyOf(issues));
    }

    @Override
    public ImpactReport analyzeImpact(ObjectRef ref) {
        ObjectRef r = requireRef(ref);
        DiffReport diff = diff(r, null);
        // 引用方与 7 天用量由 MH-08 / MH-11 接入；框架返回空依赖
        return new ImpactReport(r, diff, List.of(), 0L, 0.0d);
    }

    @Override
    public DiffReport diff(ObjectRef ref, Integer againstVersionNo) {
        ObjectRef r = requireRef(ref);
        JsonNode after = store.findDraft(r.objectType(), r.objectId())
                .map(d -> normalizer.readTree(d.getSpec()))
                .orElseGet(() -> cache.get(r).map(ConfigProjectionCache.Entry::spec).orElse(objectMapper.nullNode()));
        JsonNode before;
        if (againstVersionNo != null) {
            before = store.findVersion(r.objectType(), r.objectId(), againstVersionNo)
                    .map(v -> normalizer.readTree(v.getSpec()))
                    .orElse(objectMapper.nullNode());
        } else {
            before = cache.get(r)
                    .map(ConfigProjectionCache.Entry::spec)
                    .or(() -> currentVersionNo(r).flatMap(n ->
                            store.findVersion(r.objectType(), r.objectId(), n).map(v -> normalizer.readTree(v.getSpec()))))
                    .orElse(objectMapper.nullNode());
        }
        return new DiffReport(r, before, after);
    }

    @Override
    @Transactional
    public ConfigVersion publish(PublishCommand command) {
        ObjectRef ref = requireRef(command.ref());
        ValidationReport report = validate(ref);
        if (!report.ok()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "发布校验失败", report);
        }
        CfgDraftDO draft = store.findDraft(ref.objectType(), ref.objectId())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "草稿不存在"));
        JsonNode spec = normalizer.readTree(draft.getSpec());
        String hash = normalizer.contentHash(spec);
        int nextNo = store.maxVersionNo(ref.objectType(), ref.objectId()).orElse(0) + 1;
        Integer oldNo = currentVersionNo(ref).orElse(null);
        String previousId = cache.get(ref).map(ConfigProjectionCache.Entry::versionId).orElse(null);

        CfgVersionDO row = new CfgVersionDO();
        row.setVersionId(Ids.newId("cfgv"));
        row.setObjectType(ref.objectType());
        row.setObjectId(ref.objectId());
        row.setVersionNo(nextNo);
        row.setSpec(normalizer.toCanonicalJson(spec));
        row.setContentHash(hash);
        row.setStatus(STATUS_PUBLISHED);
        row.setPublishNote(command.note());
        row.setPublishedBy(actorId);
        row.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setPreviousVersionId(previousId);
        store.insertVersion(row);

        applyCurrent(ref, row, oldNo, "publish", command.note());
        return toApi(row, ref, spec);
    }

    @Override
    @Transactional
    public ConfigVersion rollback(RollbackCommand command) {
        ObjectRef ref = requireRef(command.ref());
        CfgVersionDO target = store.findVersion(ref.objectType(), ref.objectId(), command.targetVersionNo())
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "目标版本不存在"));
        if (!STATUS_PUBLISHED.equals(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只能回滚到已发布版本");
        }
        Integer oldNo = currentVersionNo(ref).orElse(null);
        JsonNode spec = normalizer.readTree(target.getSpec());
        applyCurrent(ref, target, oldNo, "rollback", command.reason());
        return toApi(target, ref, spec);
    }

    @Override
    public ExportBundle exportPublished(List<ObjectRef> refs) {
        List<ConfigVersion> versions = new ArrayList<>();
        if (refs != null) {
            for (ObjectRef raw : refs) {
                ObjectRef ref = requireRef(raw);
                currentVersionNo(ref).flatMap(n -> store.findVersion(ref.objectType(), ref.objectId(), n))
                        .ifPresent(v -> versions.add(toApi(v, ref, normalizer.readTree(v.getSpec()))));
            }
        }
        return new ExportBundle(EXPORT_FORMAT, List.copyOf(versions));
    }

    @Override
    @Transactional
    public List<ConfigDraft> importAsDrafts(ExportBundle bundle) {
        if (bundle == null || bundle.versions() == null) {
            return List.of();
        }
        List<ConfigDraft> out = new ArrayList<>();
        for (ConfigVersion v : bundle.versions()) {
            ObjectRef ref = requireRef(v.ref());
            JsonNode stripped = normalizer.stripAndNormalize(v.spec());
            long expected = store.findDraft(ref.objectType(), ref.objectId())
                    .map(CfgDraftDO::getRevision)
                    .orElse(0L);
            out.add(saveDraft(new SaveDraftCommand(ref, stripped, expected)));
        }
        return List.copyOf(out);
    }

    /** 只读投影当前生效版本（执行侧经本方法/缓存读取，不直接读 cfg_*）。非契约接口方法。 */
    public Optional<ConfigVersion> getCurrent(ObjectRef ref) {
        ObjectRef r = requireRef(ref);
        Optional<ConfigVersion> local = cache.getVersion(r);
        if (local.isPresent()) {
            return local;
        }
        Optional<ConfigCurrentPointerStore.Pointer> pointer = pointers.get(r);
        if (pointer.isPresent()) {
            bus.applyLocal(r, pointer.get().versionNo(), pointer.get().versionId());
            return cache.getVersion(r);
        }
        return Optional.empty();
    }

    @Override
    public Optional<ConfigDraft> getDraft(ObjectRef ref) {
        ObjectRef r = requireRef(ref);
        return store.findDraft(r.objectType(), r.objectId())
                .map(d -> new ConfigDraft(r, normalizer.readTree(d.getSpec()), d.getRevision(),
                        d.getBasedOnVersion() == null ? null : d.getBasedOnVersion().intValue()));
    }

    private void applyCurrent(
            ObjectRef ref, CfgVersionDO row, Integer oldNo, String action, String reason) {
        JsonNode spec = normalizer.readTree(row.getSpec());
        cache.put(ref, new ConfigProjectionCache.Entry(
                row.getVersionId(), row.getVersionNo(), spec, row.getContentHash(), row.getStatus()));
        pointers.set(ref, row.getVersionNo(), row.getVersionId());
        store.updateTypeTablePointer(ref.objectType(), ref.objectId(), row.getVersionId(), row.getVersionNo());

        String before = oldNo == null ? null : objectMapper.createObjectNode().put("versionNo", oldNo).toString();
        String after = objectMapper.createObjectNode()
                .put("versionNo", row.getVersionNo())
                .put("versionId", row.getVersionId())
                .put("action", action)
                .toString();
        store.insertChange(ref.objectType(), ref.objectId(), before, after, actorId, reason, null);

        bus.publishEvent(ref, row.getVersionNo(), oldNo, action, row.getVersionId());
        audit(action, ref, row, reason);
    }

    private void audit(String action, ObjectRef ref, CfgVersionDO row, String reason) {
        AuditApi api = auditApi == null ? null : auditApi.getIfAvailable();
        if (api == null) {
            return;
        }
        String auditAction = "rollback".equals(action) ? "config.rollback" : "config.publish";
        try {
            api.record(AuditEvent.builder()
                    .ts(Instant.now())
                    .actorType("system")
                    .actorId(actorId)
                    .action(auditAction)
                    .resourceType(ref.objectType())
                    .resourceId(ref.objectId())
                    .outcome(AuditOutcome.SUCCESS)
                    .reason(reason)
                    .details(Map.of(
                            "versionNo", row.getVersionNo(),
                            "versionId", row.getVersionId(),
                            "action", action))
                    .build());
        } catch (Exception ignored) {
            // 审计失败不阻断发布
        }
    }

    private Optional<Integer> currentVersionNo(ObjectRef ref) {
        return cache.get(ref).map(ConfigProjectionCache.Entry::versionNo)
                .or(() -> pointers.get(ref).map(ConfigCurrentPointerStore.Pointer::versionNo));
    }

    private static ConfigVersion toApi(CfgVersionDO row, ObjectRef ref, JsonNode spec) {
        return new ConfigVersion(
                row.getVersionId(),
                ref,
                row.getVersionNo(),
                spec,
                row.getContentHash(),
                row.getStatus());
    }

    private static ObjectRef requireRef(ObjectRef ref) {
        if (ref == null || ref.objectType() == null || ref.objectType().isBlank()
                || ref.objectId() == null || ref.objectId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "objectType/objectId 必填");
        }
        return ref;
    }
}
