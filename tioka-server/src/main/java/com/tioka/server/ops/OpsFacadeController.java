package com.tioka.server.ops;

import com.tioka.system.api.audit.Audited;

import com.tioka.agent.api.AgentShareApi;
import com.tioka.connector.api.FeishuDocSyncApi;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.NotImplemented;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.knowledge.api.DataSourceApi;
import com.tioka.knowledge.api.WikiApi;
import com.tioka.system.api.EgressQueryApi;
import com.tioka.system.api.ScimSyncApi;
import com.tioka.system.api.spi.AuditSink;
import com.tioka.system.api.spi.BizSystemAdapter;
import com.tioka.system.api.spi.ImChannelAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ops")
@RequirePermission(Permissions.TENANT_ADMIN)
public class OpsFacadeController {
    private final DataSourceApi dataSourceApi;
    private final FeishuDocSyncApi feishuDocSyncApi;
    private final WikiApi wikiApi;
    private final EgressQueryApi egressQueryApi;
    private final ScimSyncApi scimSyncApi;
    private final AgentShareApi agentShareApi;
    private final AuditSink auditSink;
    private final List<ImChannelAdapter> imAdapters;
    private final List<BizSystemAdapter> bizAdapters;
    private final String envName;

    public OpsFacadeController(DataSourceApi dataSourceApi, FeishuDocSyncApi feishuDocSyncApi,
                               WikiApi wikiApi, EgressQueryApi egressQueryApi,
                               ScimSyncApi scimSyncApi,
                               AgentShareApi agentShareApi, AuditSink auditSink,
                               List<ImChannelAdapter> imAdapters, List<BizSystemAdapter> bizAdapters,
                               @Value("${tioka.env.name:${spring.profiles.active:local}}") String envName) {
        this.dataSourceApi = dataSourceApi;
        this.feishuDocSyncApi = feishuDocSyncApi;
        this.wikiApi = wikiApi;
        this.egressQueryApi = egressQueryApi;
        this.scimSyncApi = scimSyncApi;
        this.agentShareApi = agentShareApi;
        this.auditSink = auditSink;
        this.imAdapters = imAdapters;
        this.bizAdapters = bizAdapters;
        this.envName = envName;
    }

    @PostMapping("/datasources")
    @Audited(action = "ops.datasource.bind", resourceType = "kb_datasource")
    public ApiResponse<?> ds(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(dataSourceApi.bind(body.get("kbId"), body.get("type"), body.get("uri")));
    }

    @PostMapping("/feishu/map")
    @Audited(action = "ops.feishu.map", resourceType = "feishu_doc")
    public ApiResponse<?> map(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(feishuDocSyncApi.map(body.get("localDocId"), body.get("feishuDocToken")));
    }

    @PostMapping("/wiki")
    @Audited(action = "ops.wiki.upsert", resourceType = "wiki")
    public ApiResponse<?> wiki(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(wikiApi.upsert(body.get("kbId"), body.get("title"), body.get("body")));
    }

    @GetMapping("/graph")
    public ApiResponse<?> graph(@RequestParam String kbId, @RequestParam String entity) {
        throw NotImplemented.of("graph-rag", "M4");
    }

    @GetMapping("/airgap")
    public ApiResponse<?> airgap() {
        return ApiResponse.ok(Map.of(
                "enabled", egressQueryApi.isRestricted()));
    }

    @PostMapping("/scim/sync")
    @Audited(action = "ops.scim.sync", resourceType = "scim")
    public ApiResponse<?> scim(@RequestBody List<Map<String, String>> delta) {
        return ApiResponse.ok(scimSyncApi.sync(delta));
    }

    @PostMapping("/interpreter")
    public ApiResponse<?> interp(@RequestBody Map<String, String> body) {
        throw NotImplemented.of("code-interpreter", "ADR-027");
    }

    @PostMapping("/jobs")
    public ApiResponse<?> jobs(@RequestBody Map<String, Object> body) {
        throw NotImplemented.of("automation-jobs", "ADR-027");
    }

    @PostMapping("/chat/{id}/fork")
    public ApiResponse<?> fork(@PathVariable String id) {
        throw NotImplemented.of("chat-fork", "ADR-022");
    }

    @PostMapping("/agents/{id}/share")
    @Audited(action = "ops.agent.share", resourceType = "agent", resourceIdArg = "#id")
    public ApiResponse<?> share(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(agentShareApi.share(id, body.get("mode")));
    }

    @GetMapping("/audit/recent")
    public ApiResponse<?> auditRecent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(auditSink.recent(limit));
    }

    @GetMapping("/env")
    public ApiResponse<?> env() {
        return ApiResponse.ok(Map.of("name", envName));
    }

    @GetMapping("/adapters")
    public ApiResponse<?> adapters() {
        List<String> im = new ArrayList<>();
        for (ImChannelAdapter a : imAdapters) {
            String t = a.channelType();
            if ("feishu".equals(t) || "dingtalk".equals(t) || "wecom".equals(t)) {
                im.add(t);
            }
        }
        List<String> biz = new ArrayList<>();
        for (BizSystemAdapter a : bizAdapters) {
            String t = a.systemType();
            if (t == null || "noop".equals(t)) continue;
            if (t.startsWith("example")) {
                biz.add("example");
            } else {
                biz.add(t);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("im", im.stream().distinct().toList());
        out.put("biz", biz.stream().distinct().toList());
        return ApiResponse.ok(out);
    }
}
