package com.tioka.flow.application;

import com.foundation.common.id.Ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.flow.infrastructure.FlowRepository;
import com.tioka.flow.infrastructure.persistence.FlowDraftDO;
import com.tioka.flow.infrastructure.persistence.FlowRunDO;
import com.tioka.flow.infrastructure.persistence.FlowVersionDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.context.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FlowService {
    private static final Logger log = LoggerFactory.getLogger(FlowService.class);

    public static final List<String> NODE_TYPES = List.of(
            "IO", "LLM", "Agent", "Retrieve", "HTTP", "MCP",
            "Branch", "HumanCard", "Loop", "Output", "Embedding", "Prompt", "Parser"
    );

    private final FlowRepository repo;
    private final FlowEngine engine;
    private final ObjectMapper mapper;
    /** OBS-06（ADR-075 §3）：Flow Run 开始时写运行清单；端口缺失（离线 / 单测）时跳过。 */
    private final ObjectProvider<com.tioka.governance.api.manifest.RunManifestWriter> runManifests;

    public FlowService(FlowRepository repo, FlowEngine engine, ObjectMapper mapper,
                       ObjectProvider<com.tioka.governance.api.manifest.RunManifestWriter> runManifests) {
        this.mapper = mapper;
        this.repo = repo;
        this.engine = engine;
        this.runManifests = runManifests;
    }

    public List<String> nodeTypes() {
        return NODE_TYPES;
    }

    public FlowDraft saveDraft(String name, Map<String, Object> graph) {
        validateGraph(graph);
        String id = Ids.newId("fd");
        FlowDraft d = new FlowDraft(id, name, graph);
        FlowDraftDO row = new FlowDraftDO();
        row.setId(id);
        row.setName(name);
        row.setGraphJson(toJson(graph));
        repo.insertDraft(row);
        return d;
    }

    public FlowVersion publish(String draftId) {
        FlowDraftDO draft = repo.findDraft(draftId);
        if (draft == null) throw new BizException(ErrorCode.NOT_FOUND, "draft 不存在");
        FlowDraft d = new FlowDraft(draft.getId(), draft.getName(), fromMap(draft.getGraphJson()));
        String id = Ids.newId("fv");
        FlowVersion v = new FlowVersion(id, draftId, d.name(), d.graph(), true);
        FlowVersionDO row = new FlowVersionDO();
        row.setId(id);
        row.setDraftId(draftId);
        row.setName(d.name());
        row.setGraphJson(toJson(d.graph()));
        row.setPublished(true);
        repo.insertVersion(row);
        return v;
    }

    public FlowRun run(String versionId, Map<String, Object> input) {
        return run(versionId, input, null);
    }

    /** DAT-04：Agent 工具形态携带调用方 grantId（flow_run.grant_id，V165）。 */
    public FlowRun run(String versionId, Map<String, Object> input, String grantId) {
        FlowVersionDO ver = repo.findVersion(versionId);
        if (ver == null || !Boolean.TRUE.equals(ver.getPublished())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Flow 未发布");
        }
        // FLW-02：加载版本快照时剥离 LLM 直连字段 → routingPlanId（空=FLOW 默认）
        Map<String, Object> graph = LlmNodeConfigMigrator.migrate(fromMap(ver.getGraphJson()));
        String id = Ids.newId("fr");
        FlowEngine.EngineResult result = engine.run(id, graph, input == null ? Map.of() : input, grantId);

        FlowRun run = new FlowRun(id, versionId, result.status(), result.output(), result.trace());
        FlowRunDO row = new FlowRunDO();
        row.setId(id);
        row.setVersionId(versionId);
        row.setStatus(result.status());
        row.setTraceJson(toJsonList(result.trace()));
        row.setGrantId(grantId);
        repo.insertRun(row);
        writeRunManifest(versionId, id, grantId);
        return run;
    }

    /**
     * OBS-06（ADR-075 §3）：Flow Run 开始时写运行清单（Flow 版本、授予 ID 等快照），
     * 供统一时间线展开与同类运行差异对比。清单写入失败只记日志不阻断 Run（观测面）。
     */
    private void writeRunManifest(String versionId, String runId, String grantId) {
        var writer = runManifests.getIfAvailable();
        if (writer == null) {
            return;
        }
        try {
            var correlation = RequestContextHolder.getCorrelation();
            writer.write(new com.tioka.governance.api.manifest.RunManifestWriteRequest(
                    "FLOW_RUN", runId, correlation.requestId(), null, null, grantId, null,
                    Map.of("flowVersionId", versionId)));
        } catch (RuntimeException e) {
            log.warn("run_manifest 写入失败 flowRunId={}: {}", runId, e.toString());
        }
    }

    /** 供单测 / 内部直接跑图（不落库）。 */
    public FlowEngine.EngineResult runGraph(Map<String, Object> graph, Map<String, Object> input) {
        String id = Ids.newId("fr");
        Map<String, Object> migrated = LlmNodeConfigMigrator.migrate(graph);
        return engine.run(id, migrated, input == null ? Map.of() : input);
    }

    public void validateGraph(Map<String, Object> graph) {
        FlowGraphValidator.validate(graph, NODE_TYPES);
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJsonList(List<Map<String, Object>> list) {
        try {
            return mapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> fromMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST, "JSON 解析失败");
        }
    }

    public record FlowDraft(String id, String name, Map<String, Object> graph) {}
    public record FlowVersion(String id, String draftId, String name, Map<String, Object> graph, boolean published) {}
    public record FlowRun(String id, String versionId, String status, Object output, List<Map<String, Object>> trace) {
        public FlowRun(String id, String versionId, String status, List<Map<String, Object>> trace) {
            this(id, versionId, status, null, trace);
        }
    }
}
