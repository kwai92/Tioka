package com.tioka.flow.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.flow.infrastructure.FlowRepository;
import com.tioka.flow.infrastructure.persistence.FlowDraftDO;
import com.tioka.flow.infrastructure.persistence.FlowRunDO;
import com.tioka.flow.infrastructure.persistence.FlowVersionDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FlowService {
    public static final List<String> NODE_TYPES = List.of(
            "IO", "LLM", "Agent", "Retrieve", "HTTP", "MCP",
            "Branch", "HumanCard", "Loop", "Output", "Embedding", "Prompt", "Parser"
    );

    private final FlowRepository repo;
    private final FlowEngine engine;
    private final ObjectMapper mapper;

    public FlowService(FlowRepository repo, FlowEngine engine, ObjectMapper mapper) {
        this.mapper = mapper;
        this.repo = repo;
        this.engine = engine;
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
        FlowVersionDO ver = repo.findVersion(versionId);
        if (ver == null || !Boolean.TRUE.equals(ver.getPublished())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Flow 未发布");
        }
        // FLW-02：加载版本快照时剥离 LLM 直连字段 → routingPlanId（空=FLOW 默认）
        Map<String, Object> graph = LlmNodeConfigMigrator.migrate(fromMap(ver.getGraphJson()));
        String id = Ids.newId("fr");
        FlowEngine.EngineResult result = engine.run(id, graph, input == null ? Map.of() : input);

        FlowRun run = new FlowRun(id, versionId, result.status(), result.output(), result.trace());
        FlowRunDO row = new FlowRunDO();
        row.setId(id);
        row.setVersionId(versionId);
        row.setStatus(result.status());
        row.setTraceJson(toJsonList(result.trace()));
        repo.insertRun(row);
        return run;
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
