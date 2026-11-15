package com.tioka.governance.application.manifest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.context.RequestContext;
import com.tioka.framework.context.RequestContextHolder;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.trace.TraceContext;
import com.tioka.governance.api.manifest.RunManifestWriteRequest;
import com.tioka.governance.api.manifest.RunManifestWriter;
import com.tioka.governance.api.mq.GovernanceMqTopics;
import com.tioka.governance.api.timeline.TimelineProjector;
import com.tioka.governance.application.timeline.RunManifestTimelineProjection;
import com.tioka.governance.infrastructure.manifest.RunManifestRepository;
import com.tioka.governance.infrastructure.persistence.RunManifestDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 运行清单写入（OBS-06 / ADR-075 §3）。
 *
 * <p>调用时机：每个 Turn、编排 Run、Flow Run 开始时写一条；续跑时若清单内容有变化则追加一条
 * （{@code seq} 递增），内容不变则复用最近一条（幂等，避免同一运行重复落库）。
 *
 * <p>落库与 {@code tioka.obs.run_manifest_written.v1} 事件登记在同一事务（outbox，ORC-02）。
 * 关联键 {@code requestId} / {@code subject} / {@code traceId} 优先取请求参数，缺失时从
 * {@link RequestContextHolder}（ADR-073）与 {@link TraceContext} 兜底，保证与审计、traceId 贯通。
 *
 * <p>{@code contentHash} 只覆盖「同类运行可比」的内容（runKind、Agent 版本与 spec 哈希、授权书、
 * 版本集合），不含 runId / seq，因此同类运行的差异对比直接比较该哈希即可（ADR-075 §3 第 55 行）。
 */
public class DefaultRunManifestWriter implements RunManifestWriter {

    private static final Logger log = LoggerFactory.getLogger(DefaultRunManifestWriter.class);

    private final RunManifestRepository repository;
    /** 事件出口；缺席（单元测试）时只落库不发布。 */
    private final EventPublisher publisher;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;
    /** 时间线投影（OBS-08）；缺席（OBS-08 未装配）时只落清单不投影。 */
    private final TimelineProjector timelineProjector;

    public DefaultRunManifestWriter(RunManifestRepository repository,
                                    EventPublisher publisher,
                                    PrincipalHolder principals,
                                    ObjectMapper mapper) {
        this(repository, publisher, principals, mapper, null);
    }

    public DefaultRunManifestWriter(RunManifestRepository repository,
                                    EventPublisher publisher,
                                    PrincipalHolder principals,
                                    ObjectMapper mapper,
                                    TimelineProjector timelineProjector) {
        this.repository = repository;
        this.publisher = publisher;
        this.principals = principals;
        this.mapper = mapper;
        this.timelineProjector = timelineProjector;
    }

    @Override
    @Transactional
    public String write(RunManifestWriteRequest request) {
        String runKind = require(request.runKind(), "runKind");
        String runId = require(request.runId(), "runId");
        Map<String, String> versions = request.versions() == null ? Map.of() : request.versions();

        String requestId = firstNonBlank(request.requestId(),
                RequestContextHolder.getCorrelation().requestId());
        String parentRequestId = RequestContextHolder.getCorrelation().parentRequestId();
        String subject = RequestContextHolder.getCorrelation().subject();
        String workspaceId = firstNonBlank(request.workspaceId(), currentWorkspaceId());
        String traceId = TraceContext.getTraceId();
        String agentVersionId = blankToNull(request.agentVersionId());
        String specHash = blankToNull(request.specHash());
        String grantId = blankToNull(request.grantId());

        String contentHash = hashCode(canonical(runKind, agentVersionId, specHash, grantId, versions));
        RunManifestDO latest = repository.findLatest(runKind, runId);
        if (latest != null && contentHash.equals(latest.getContentHash())) {
            // 续跑且清单无变化：不追加新行（ADR-075 §3「续跑时如有变化追加一条」）
            return latest.getId();
        }
        int seq = latest == null ? 1 : latest.getSeq() + 1;

        RunManifestDO row = new RunManifestDO();
        row.setId(Ids.newId("rmf"));
        row.setRunKind(runKind);
        row.setRunId(runId);
        row.setSeq(seq);
        row.setRequestId(requestId);
        row.setParentRequestId(parentRequestId);
        row.setWorkspaceId(workspaceId);
        row.setSubject(subject);
        row.setTraceId(traceId);
        row.setAgentVersionId(agentVersionId);
        row.setSpecHash(specHash);
        row.setGrantId(grantId);
        row.setManifestJson(toJson(runKind, runId, seq, agentVersionId, specHash, grantId, versions));
        row.setContentHash(contentHash);
        repository.insert(row);

        projectRun(row);
        publish(row, requestId, subject);
        return row.getId();
    }

    /**
     * OBS-08 / ADR-075 §2：把运行开始投影为时间线 {@code RUN} 条目（清单可展开）。
     * 失败静默降级（投影不是事实源）；缺席（OBS-08 未装配）时跳过。
     * 映射与重建共用 {@link RunManifestTimelineProjection}，两侧条目一致。
     */
    private void projectRun(RunManifestDO row) {
        if (timelineProjector == null) {
            return;
        }
        try {
            timelineProjector.project(RunManifestTimelineProjection.toSourceEvent(row));
        } catch (RuntimeException e) {
            log.warn("时间线投影失败 manifestId={}: {}", row.getId(), e.toString());
        }
    }

    /** 事件契约见 {@code docs/contracts/events/tioka.obs.run_manifest_written.v1.json}。 */
    private void publish(RunManifestDO row, String requestId, String subject) {
        if (publisher == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manifestId", row.getId());
        payload.put("runRef", row.getRunKind() + ":" + row.getRunId());
        payload.put("runKind", row.getRunKind());
        if (row.getAgentVersionId() != null) {
            payload.put("agentVersionId", row.getAgentVersionId());
        }
        payload.put("workspaceId", row.getWorkspaceId() == null ? "" : row.getWorkspaceId());
        try {
            publisher.publish(new DomainEvent(
                    GovernanceMqTopics.RUN_MANIFEST_WRITTEN,
                    GovernanceMqTopics.TOPIC,
                    row.getWorkspaceId(),
                    payload,
                    0,
                    requestId,
                    subject));
        } catch (RuntimeException e) {
            log.warn("run_manifest_written 事件登记失败 manifestId={}: {}", row.getId(), e.toString());
        }
    }

    private String toJson(String runKind, String runId, int seq, String agentVersionId,
                          String specHash, String grantId, Map<String, String> versions) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("runKind", runKind);
        content.put("runId", runId);
        content.put("seq", seq);
        content.put("agentVersionId", agentVersionId);
        content.put("specHash", specHash);
        content.put("grantId", grantId);
        content.put("versions", versions);
        try {
            return mapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("运行清单序列化失败", e);
        }
    }

    /**
     * 差异对比口径（ADR-075 §3）：固定字段顺序 + 版本集合按键排序，null→空串。
     * 不含 runId / seq，故同类可比的只有内容本身。
     */
    static String canonical(String runKind, String agentVersionId, String specHash,
                           String grantId, Map<String, String> versions) {
        StringBuilder sb = new StringBuilder()
                .append(n(runKind)).append('|')
                .append(n(agentVersionId)).append('|')
                .append(n(specHash)).append('|')
                .append(n(grantId));
        if (versions != null && !versions.isEmpty()) {
            for (Map.Entry<String, String> e : new TreeMap<>(versions).entrySet()) {
                sb.append('|').append(n(e.getKey())).append('=').append(n(e.getValue()));
            }
        }
        return sb.toString();
    }

    /** SHA-256 十六进制摘要（小写），与审计哈希链同口径。 */
    static String hashCode(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String currentWorkspaceId() {
        Principal p = principals.find().orElse(null);
        return p == null ? null : p.workspaceId();
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("运行清单缺少必填字段：" + field);
        }
        return v;
    }

    private static String n(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String firstNonBlank(String a, String b) {
        return a == null || a.isBlank() ? blankToNull(b) : a;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
