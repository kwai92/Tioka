package com.tioka.server.ops;

import com.tioka.agent.application.AgentShareService;
import com.tioka.connector.application.FeishuDocSyncService;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.NotImplemented;
import com.tioka.knowledge.application.DataSourceService;
import com.tioka.knowledge.application.WikiService;
import com.tioka.system.api.spi.BizSystemAdapter;
import com.tioka.system.api.spi.ImChannelAdapter;
import com.tioka.system.application.AirGapService;
import com.tioka.system.application.ScimSyncService;
import com.tioka.system.infrastructure.spi.FileAuditSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ops")
public class OpsFacadeController {
    private final DataSourceService dataSourceService;
    private final FeishuDocSyncService feishuDocSyncService;
    private final WikiService wikiService;
    private final AirGapService airGapService;
    private final ScimSyncService scimSyncService;
    private final AgentShareService agentShareService;
    private final FileAuditSink auditSink;
    private final List<ImChannelAdapter> imAdapters;
    private final List<BizSystemAdapter> bizAdapters;
    private final String envName;

    public OpsFacadeController(DataSourceService dataSourceService, FeishuDocSyncService feishuDocSyncService,
                               WikiService wikiService, AirGapService airGapService,
                               ScimSyncService scimSyncService,
                               AgentShareService agentShareService, FileAuditSink auditSink,
                               List<ImChannelAdapter> imAdapters, List<BizSystemAdapter> bizAdapters,
                               @Value("${tioka.env.name:${spring.profiles.active:local}}") String envName) {
        this.dataSourceService = dataSourceService;
        this.feishuDocSyncService = feishuDocSyncService;
        this.wikiService = wikiService;
        this.airGapService = airGapService;
        this.scimSyncService = scimSyncService;
        this.agentShareService = agentShareService;
        this.auditSink = auditSink;
        this.imAdapters = imAdapters;
        this.bizAdapters = bizAdapters;
        this.envName = envName;
    }

    @PostMapping("/datasources")
    public ApiResponse<?> ds(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(dataSourceService.bind(body.get("kbId"), body.get("type"), body.get("uri")));
    }

    @PostMapping("/feishu/map")
    public ApiResponse<?> map(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(feishuDocSyncService.map(body.get("localDocId"), body.get("feishuDocToken")));
    }

    @PostMapping("/wiki")
    public ApiResponse<?> wiki(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(wikiService.upsert(body.get("kbId"), body.get("title"), body.get("body")));
    }

    @GetMapping("/graph")
    public ApiResponse<?> graph(@RequestParam String kbId, @RequestParam String entity) {
        throw NotImplemented.of("graph-rag", "M4");
    }

    @GetMapping("/airgap")
    public ApiResponse<?> airgap() { return ApiResponse.ok(Map.of("enabled", airGapService.enabled())); }

    @PostMapping("/scim/sync")
    public ApiResponse<?> scim(@RequestBody List<Map<String, String>> delta) { return ApiResponse.ok(scimSyncService.sync(delta)); }

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
    public ApiResponse<?> share(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(agentShareService.share(id, body.get("mode")));
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
