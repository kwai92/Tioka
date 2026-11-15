package com.tioka.system.application.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.config.ConfigVersionApi;
import com.tioka.governance.api.manifest.RunManifestQuery;
import com.tioka.governance.api.manifest.RunManifestView;
import com.tioka.governance.api.timeline.TimelineCategory;
import com.tioka.governance.api.timeline.TimelineEntryView;
import com.tioka.governance.api.timeline.TimelinePageSpec;
import com.tioka.governance.api.timeline.TimelineQuery;
import com.tioka.runtime.api.approval.ApprovalQueryApi;
import com.tioka.runtime.api.approval.ApprovalView;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.authz.SystemRoles;
import com.tioka.system.api.evidence.EvidencePackSigner;
import com.tioka.system.api.obs.ExtInteractionQueryApi;
import com.tioka.system.api.obs.ExtInteractionView;
import com.tioka.system.application.audit.AuditChainService;
import com.tioka.system.application.audit.AuditService;
import com.tioka.system.infrastructure.EvidencePackRepository;
import com.tioka.system.infrastructure.persistence.EvidencePackDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 证据包导出（OBS-12 / M4-06 §7 / ADR-075 §5）。
 *
 * <p>对一个 {@code subject} 或 {@code request_id} 装配七段：统一时间线、相关审计、外部交互、
 * 运行清单、审批与处置、相关配置版本、以及对应哈希链封印记录与校验结果；对文件清单计算各文件
 * SHA-256 与包整体哈希，并由平台密钥签名；登记 {@code evidence_pack} 并写审计
 * {@code timeline.evidence.export}（ADR-072 §6）。
 *
 * <p>CONFIDENTIAL 内容按导出人权限决定是否包含：平台级或持 {@code trajectory:read:content} 者
 * 上限为 {@code SECRET}，否则为 {@code INTERNAL}（与时间线查看口径一致）。
 */
@Service
public class EvidencePackService {
    private static final Logger log = LoggerFactory.getLogger(EvidencePackService.class);

    /** 导出审计动作名（登记于 docs/contracts/audit/actions.yaml）。 */
    static final String ACTION_EXPORT = "timeline.evidence.export";

    public static final String SCOPE_SUBJECT = "SUBJECT";
    public static final String SCOPE_REQUEST = "REQUEST";

    private static final String SECTION_TIMELINE = "timeline";
    private static final String SECTION_AUDIT = "audit";
    private static final String SECTION_EXT = "ext-interaction";
    private static final String SECTION_MANIFEST = "run-manifest";
    private static final String SECTION_APPROVALS = "approvals";
    private static final String SECTION_CONFIG = "config-versions";
    private static final String SECTION_SEAL = "audit-seal";

    private static final int MAX_PACK_ROWS = 5_000;
    private static final int MANIFEST_LIMIT = 500;
    private static final int EXT_LIMIT = 1_000;

    private final TimelineQuery timelineQuery;
    private final RunManifestQuery runManifestQuery;
    private final ConfigVersionApi configVersionApi;
    private final ApprovalQueryApi approvalQuery;
    private final ExtInteractionQueryApi extInteraction;
    private final AuditService auditService;
    private final AuditChainService auditChain;
    private final EvidencePackSigner signer;
    private final EvidencePackRepository repository;
    private final PrincipalHolder principals;
    private final AuditDegradedSink degraded;
    private final ObjectMapper mapper;

    public EvidencePackService(TimelineQuery timelineQuery,
                               RunManifestQuery runManifestQuery,
                               ConfigVersionApi configVersionApi,
                               ApprovalQueryApi approvalQuery,
                               ExtInteractionQueryApi extInteraction,
                               AuditService auditService,
                               AuditChainService auditChain,
                               EvidencePackSigner signer,
                               EvidencePackRepository repository,
                               PrincipalHolder principals,
                               AuditDegradedSink degraded,
                               ObjectMapper mapper) {
        this.timelineQuery = timelineQuery;
        this.runManifestQuery = runManifestQuery;
        this.configVersionApi = configVersionApi;
        this.approvalQuery = approvalQuery;
        this.extInteraction = extInteraction;
        this.auditService = auditService;
        this.auditChain = auditChain;
        this.signer = signer;
        this.repository = repository;
        this.principals = principals;
        this.degraded = degraded;
        this.mapper = mapper;
    }

    /** 导出请求：范围（{@code scopeType} + {@code scopeRef}）、可选空间限定、可选相关配置对象。 */
    public record ExportRequest(String scopeType, String scopeRef, String workspaceId,
                                List<String> configObjects) {}

    /** 导出结果：包字节 + 清单与签名元信息。 */
    public record ExportResult(String packId, String scopeType, String scopeRef, String workspaceId,
                               String clearance, boolean includeConfidential, int fileCount, int rowCount,
                               String packageHash, String signatureAlg, String keyId, String signature,
                               List<String> omittedSections, byte[] content) {}

    public ExportResult export(ExportRequest request) {
        Principal p = principals.current();
        String scopeType = normalizeScopeType(request.scopeType());
        String scopeRef = requireText(request.scopeRef(), "scopeRef");
        boolean requestScope = SCOPE_REQUEST.equals(scopeType);

        boolean platform = isPlatformAdmin(p);
        DataLabel clearance = platform || p.hasPermission(Permissions.TRAJECTORY_READ_CONTENT)
                ? DataLabel.SECRET : DataLabel.INTERNAL;
        boolean includeConfidential = clearance.ordinal() >= DataLabel.CONFIDENTIAL.ordinal();
        String workspaceId = platform ? blankToNull(request.workspaceId()) : p.workspaceId();

        String requestId = requestScope ? scopeRef : null;
        String subjectType = null;
        String subjectId = null;
        if (!requestScope) {
            int i = scopeRef.indexOf(':');
            if (i <= 0 || i == scopeRef.length() - 1) {
                throw new BizException(ErrorCode.BAD_REQUEST, "subject 范围引用形如 subjectType:subjectId");
            }
            subjectType = scopeRef.substring(0, i);
            subjectId = scopeRef.substring(i + 1);
        }

        List<TimelineEntryView> timeline = loadTimeline(requestScope, scopeRef, subjectType, subjectId,
                clearance, workspaceId);
        List<AuditEvent> audits = loadAudits(workspaceId, requestId, requestScope ? null : scopeRef);
        List<ExtInteractionView> ext = loadExtInteractions(requestId, subjectType, subjectId);
        List<RunManifestView> manifests = runManifestQuery.listByScope(
                workspaceId, requestId, requestScope ? null : scopeRef, MANIFEST_LIMIT);
        if (manifests == null) {
            manifests = List.of();
        }
        List<ApprovalView> approvals = loadApprovals(timeline);
        List<ConfigVersionApi.ConfigVersion> configVersions =
                loadConfigVersions(request.configObjects());

        List<String> omitted = new ArrayList<>();
        if (!includeConfidential) {
            omitted.add("confidential");
            int before = ext.size();
            ext = filterConfidential(ext, clearance);
            if (ext.size() != before) {
                omitted.add(SECTION_EXT + ".confidential");
            }
        }

        List<AuditChainService.ChainVerifyResult> seals =
                loadSeals(timeline, audits, manifests, workspaceId, platform);

        SortedMap<String, String> sections = new TreeMap<>();
        sections.put(SECTION_TIMELINE, json(timeline));
        sections.put(SECTION_AUDIT, json(audits));
        sections.put(SECTION_EXT, json(ext));
        sections.put(SECTION_MANIFEST, json(manifests));
        sections.put(SECTION_APPROVALS, json(approvals));
        sections.put(SECTION_CONFIG, json(configVersions));
        sections.put(SECTION_SEAL, json(seals));

        SortedMap<String, String> fileHashes = new TreeMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        for (Map.Entry<String, String> e : sections.entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            String sha = EvidencePackVerifier.sha256Hex(bytes);
            fileHashes.put(e.getKey(), sha);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", e.getKey());
            f.put("sha256", sha);
            f.put("length", bytes.length);
            f.put("content", e.getValue());
            files.add(f);
        }

        String packageHash = EvidencePackVerifier.packageHash(fileHashes);
        String signature = signer.sign(packageHash.getBytes(StandardCharsets.UTF_8));

        int rowCount = timeline.size() + audits.size() + ext.size() + manifests.size()
                + approvals.size() + configVersions.size() + seals.size();

        String packId = Ids.newId("evp");
        LocalDateTime exportedAt = LocalDateTime.now(ZoneOffset.UTC);

        Map<String, Object> manifestMeta = new LinkedHashMap<>();
        manifestMeta.put("packId", packId);
        manifestMeta.put("scopeType", scopeType);
        manifestMeta.put("scopeRef", scopeRef);
        manifestMeta.put("workspaceId", workspaceId);
        manifestMeta.put("exportedBy", p.userId());
        manifestMeta.put("exportedAt", exportedAt.toInstant(ZoneOffset.UTC).toString());
        manifestMeta.put("clearance", clearance.name());
        manifestMeta.put("includeConfidential", includeConfidential);
        manifestMeta.put("signatureAlg", signer.algorithm());
        manifestMeta.put("keyId", signer.keyId());
        manifestMeta.put("fileCount", files.size());
        manifestMeta.put("rowCount", rowCount);
        manifestMeta.put("omittedSections", omitted);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("formatVersion", 1);
        bundle.put("manifest", manifestMeta);
        bundle.put("files", files);
        bundle.put("packageHash", packageHash);
        bundle.put("signature", signature);
        byte[] content = json(bundle).getBytes(StandardCharsets.UTF_8);

        EvidencePackDO row = new EvidencePackDO();
        row.setId(packId);
        row.setScopeType(scopeType);
        row.setScopeRef(scopeRef);
        row.setWorkspaceId(workspaceId);
        row.setExportedBy(p.userId());
        row.setExportedAt(exportedAt);
        row.setClearance(clearance.name());
        row.setIncludeConfidential(includeConfidential);
        row.setOmittedSections(omitted.isEmpty() ? null : json(omitted));
        row.setFileCount(files.size());
        row.setRowCount(rowCount);
        row.setPackageHash(packageHash);
        row.setSignatureAlg(signer.algorithm());
        row.setKeyId(signer.keyId());
        row.setSignature(signature);
        repository.save(row);

        writeAudit(packId, scopeType, scopeRef, workspaceId, files.size(), rowCount,
                packageHash, includeConfidential, omitted);

        return new ExportResult(packId, scopeType, scopeRef, workspaceId, clearance.name(),
                includeConfidential, files.size(), rowCount, packageHash, signer.algorithm(),
                signer.keyId(), signature, List.copyOf(omitted), content);
    }

    /* ---------- 各段装配 ---------- */

    private List<TimelineEntryView> loadTimeline(boolean requestScope, String scopeRef,
                                                 String subjectType, String subjectId,
                                                 DataLabel clearance, String workspaceId) {
        List<TimelineEntryView> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (all.size() < MAX_PACK_ROWS && pages++ < 200) {
            TimelinePageSpec spec = new TimelinePageSpec(Set.of(), null, null, cursor,
                    TimelinePageSpec.MAX_LIMIT, clearance, workspaceId);
            List<TimelineEntryView> page = requestScope
                    ? timelineQuery.pageByRequest(scopeRef, spec)
                    : timelineQuery.pageBySubject(subjectType, subjectId, spec);
            if (page == null || page.isEmpty()) {
                break;
            }
            all.addAll(page);
            if (page.size() < TimelinePageSpec.MAX_LIMIT) {
                break;
            }
            TimelineEntryView last = page.get(page.size() - 1);
            cursor = last.occurredAt().toEpochMilli() + ":" + last.id();
        }
        return all.size() > MAX_PACK_ROWS ? all.subList(0, MAX_PACK_ROWS) : all;
    }

    private List<AuditEvent> loadAudits(String workspaceId, String requestId, String subject) {
        AuditApi.AuditQuery q = new AuditApi.AuditQuery(workspaceId, null, null, null, null, null,
                null, null, null, requestId, subject, null, null, null, MAX_PACK_ROWS);
        List<AuditEvent> rows = auditService.queryForExport(q, MAX_PACK_ROWS);
        return rows == null ? List.of() : rows;
    }

    private List<ExtInteractionView> loadExtInteractions(String requestId, String subjectType, String subjectId) {
        String subject = subjectType == null ? null : subjectType + ":" + subjectId;
        List<ExtInteractionView> rows = extInteraction.query(new ExtInteractionQueryApi.Query(
                requestId, subject, null, null, null, null, null, null, EXT_LIMIT, 0));
        return rows == null ? List.of() : rows;
    }

    private static List<ExtInteractionView> filterConfidential(List<ExtInteractionView> rows, DataLabel clearance) {
        List<ExtInteractionView> kept = new ArrayList<>(rows.size());
        for (ExtInteractionView v : rows) {
            DataLabel label = parseLabel(v.dataLabel());
            if (label != null && !label.atMost(clearance)) {
                continue;
            }
            kept.add(v);
        }
        return kept;
    }

    private List<ApprovalView> loadApprovals(List<TimelineEntryView> timeline) {
        Set<String> ids = new LinkedHashSet<>();
        for (TimelineEntryView e : timeline) {
            if (e.category() == TimelineCategory.APPROVAL && e.sourceId() != null) {
                ids.add(e.sourceId());
            }
        }
        List<ApprovalView> approvals = new ArrayList<>();
        for (String id : ids) {
            try {
                ApprovalView v = approvalQuery.get(id);
                if (v != null) {
                    approvals.add(v);
                }
            } catch (RuntimeException ex) {
                log.debug("evidence pack: approval {} skipped: {}", id, ex.getMessage());
            }
        }
        return approvals;
    }

    private List<ConfigVersionApi.ConfigVersion> loadConfigVersions(List<String> objects) {
        List<ConfigVersionApi.ConfigVersion> versions = new ArrayList<>();
        if (objects == null) {
            return versions;
        }
        for (String ref : objects) {
            if (ref == null) {
                continue;
            }
            int i = ref.indexOf(':');
            if (i <= 0 || i == ref.length() - 1) {
                continue;
            }
            try {
                configVersionApi.getCurrent(new ConfigVersionApi.ObjectRef(ref.substring(0, i), ref.substring(i + 1)))
                        .ifPresent(versions::add);
            } catch (RuntimeException ex) {
                log.debug("evidence pack: config {} skipped: {}", ref, ex.getMessage());
            }
        }
        return versions;
    }

    private List<AuditChainService.ChainVerifyResult> loadSeals(List<TimelineEntryView> timeline,
                                                                List<AuditEvent> audits,
                                                                List<RunManifestView> manifests,
                                                                String workspaceId, boolean platform) {
        Set<String> keys = new LinkedHashSet<>();
        for (TimelineEntryView e : timeline) {
            if (e.workspaceId() != null) {
                keys.add(e.workspaceId());
            }
        }
        for (AuditEvent a : audits) {
            if (a.workspaceId() != null) {
                keys.add(a.workspaceId());
            }
        }
        for (RunManifestView m : manifests) {
            if (m.workspaceId() != null) {
                keys.add(m.workspaceId());
            }
        }
        if (platform) {
            keys.add(AuditChainService.SYSTEM_CHAIN);
        } else if (keys.isEmpty()) {
            keys.add(workspaceId != null ? workspaceId : AuditChainService.SYSTEM_CHAIN);
        }
        List<AuditChainService.ChainVerifyResult> seals = new ArrayList<>();
        for (String key : keys) {
            if (!platform && !key.equals(workspaceId)) {
                continue;
            }
            try {
                seals.add(auditChain.verify(key));
            } catch (RuntimeException ex) {
                log.debug("evidence pack: seal verify {} skipped: {}", key, ex.getMessage());
            }
        }
        return seals;
    }

    /* ---------- 审计与工具 ---------- */

    private void writeAudit(String packId, String scopeType, String scopeRef, String workspaceId,
                            int fileCount, int rowCount, String packageHash,
                            boolean includeConfidential, List<String> omitted) {
        try {
            Principal p = principals.find().orElse(null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("scopeType", scopeType);
            details.put("scopeRef", scopeRef);
            details.put("fileCount", fileCount);
            details.put("rowCount", rowCount);
            details.put("packageHash", packageHash);
            details.put("includeConfidential", includeConfidential);
            if (!omitted.isEmpty()) {
                details.put("omittedSections", omitted);
            }
            auditService.record(AuditEvent.builder()
                    .ts(Instant.now())
                    .workspaceId(workspaceId)
                    .actorType(p == null ? "system" : p.type().name().toLowerCase(Locale.ROOT))
                    .actorId(p == null ? "system" : p.userId())
                    .action(ACTION_EXPORT)
                    .resourceType("evidence_pack")
                    .resourceId(packId)
                    .outcome(AuditOutcome.SUCCESS)
                    .details(details)
                    .build());
        } catch (RuntimeException ex) {
            try {
                degraded.append(AuditEvent.builder()
                        .ts(Instant.now())
                        .action(ACTION_EXPORT)
                        .resourceType("evidence_pack")
                        .resourceId(packId)
                        .outcome(AuditOutcome.ERROR)
                        .reason(ex.getMessage())
                        .build());
            } catch (RuntimeException ignored) {
                // 降级留痕也失败时不再向上抛
            }
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("证据包序列化失败: " + e.getMessage(), e);
        }
    }

    private static String normalizeScopeType(String raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scopeType 必填（SUBJECT / REQUEST）");
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        if (!SCOPE_SUBJECT.equals(v) && !SCOPE_REQUEST.equals(v)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "scopeType 只支持 SUBJECT / REQUEST");
        }
        return v;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, field + " 必填");
        }
        return value.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static DataLabel parseLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DataLabel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isPlatformAdmin(Principal p) {
        return p.hasPermission(Permissions.TENANT_ADMIN) || p.hasPermission(Permissions.ALL)
                || p.roles().contains(SystemRoles.SYSTEM_ADMIN);
    }
}
