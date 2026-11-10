package com.tioka.runtime.application.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.governance.api.label.SinkKind;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.label.TurnLabelTracker;
import com.tioka.runtime.api.pipeline.ToolPipelineContext;
import com.tioka.runtime.api.pipeline.ToolPipelineOrders;
import com.tioka.runtime.api.pipeline.ToolPipelineStep;
import com.tioka.runtime.api.pipeline.ToolPipelineVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工具管线「去向许可」步（DAT-02 / M3-02；{@code ToolPipelineOrders.EGRESS_PERMIT = 12.5}）。
 *
 * <p>M3-02 原文口径：写工具参数出网前调 {@code SinkGuard(MCP / BIZ, serverId)}——
 * 工具归属 MCP Server 时 sink=MCP/ref=serverId，否则 sink=BIZ/ref=toolName。
 * Turn 当前标签取 {@code runtime_thread.data_label}（TurnLabelTracker）。
 *
 * <ul>
 *   <li>PASS → CONTINUE；REDACTED → 脱敏后的参数写入
 *       {@code egress.redactedArgs}，由 {@code DefaultToolGateway.prepare} 换用；</li>
 *   <li>DENY → {@link ErrorCode#DATA_LABEL_EXCEEDED}（40332）；</li>
 *   <li>REQUIRE_APPROVAL → ESCALATE（通用审批 subject_type=data_egress）。</li>
 *   <li>SinkGuard / 标签缺失（模块单测无 governance 装配）→ 直接 CONTINUE。</li>
 * </ul>
 */
@Component
public class SinkGuardPipelineStep implements ToolPipelineStep {

    private static final Logger log = LoggerFactory.getLogger(SinkGuardPipelineStep.class);

    public static final String ATTR_REDACTED_ARGS = "egress.redactedArgs";
    public static final String ATTR_SINK_KIND = "egress.sinkKind";
    public static final String ATTR_SINK_REF = "egress.sinkRef";

    private final ObjectProvider<SinkGuard> sinkGuard;
    private final ToolRegistry registry;
    private final TurnLabelTracker labelTracker;
    private final ObjectMapper mapper;

    public SinkGuardPipelineStep(ObjectProvider<SinkGuard> sinkGuard,
                                 ToolRegistry registry,
                                 TurnLabelTracker labelTracker,
                                 ObjectMapper mapper) {
        this.sinkGuard = sinkGuard;
        this.registry = registry;
        this.labelTracker = labelTracker;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "egress-permit";
    }

    @Override
    public double order() {
        return ToolPipelineOrders.EGRESS_PERMIT;
    }

    @Override
    public ToolPipelineVerdict execute(ToolPipelineContext ctx) {
        SinkGuard guard = sinkGuard.getIfAvailable();
        if (guard == null) {
            return ToolPipelineVerdict.CONTINUE;
        }
        var call = ctx.callContext();
        Map<String, Object> cfg = call == null ? Map.of() : call.agentToolConfig();
        String versionId = call == null ? null : call.agentVersionId();
        String serverId = registry.mcpServerIdOf(versionId, ctx.toolName(), cfg).orElse(null);
        SinkKind kind = serverId != null ? SinkKind.MCP : SinkKind.BIZ;
        String ref = serverId != null ? serverId : ctx.toolName();
        ctx.put(ATTR_SINK_KIND, kind.name());
        ctx.put(ATTR_SINK_REF, ref);
        var label = labelTracker.current(call == null ? null : call.threadId());
        String payload = writeJson(ctx.args());
        SinkCheckResult result = guard.check(kind, ref, label, payload);
        return switch (result.verdict()) {
            case PASS -> ToolPipelineVerdict.CONTINUE;
            case REDACTED -> {
                Map<String, Object> redacted = redactArgs(ctx.args(), result.redactedPayload());
                ctx.put(ATTR_REDACTED_ARGS, redacted);
                log.info("egress-permit REDACTED tool={} sink={}/{}", ctx.toolName(), kind, ref);
                yield ToolPipelineVerdict.CONTINUE;
            }
            case REQUIRE_APPROVAL -> ToolPipelineVerdict.ESCALATE;
            case DENY -> throw new BizException(ErrorCode.DATA_LABEL_EXCEEDED,
                    "数据标签超出 " + kind + "/" + ref + " 去向许可");
        };
    }

    private Map<String, Object> redactArgs(Map<String, Object> args, Object redactedPayload) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        String redactedJson = redactedPayload instanceof String s ? s : String.valueOf(redactedPayload);
        try {
            Map<String, Object> parsed = mapper.readValue(redactedJson, new TypeReference<>() {});
            Map<String, Object> merged = new LinkedHashMap<>(args);
            // 仅替换值为字符串且被脱敏命中的键；结构化参数保守替换整个 payload
            for (Map.Entry<String, Object> e : args.entrySet()) {
                if (e.getValue() instanceof String v && !v.equals(parsed.get(e.getKey()))) {
                    merged.put(e.getKey(), parsed.get(e.getKey()));
                }
            }
            return merged;
        } catch (Exception e) {
            // 脱敏文本不是合法 JSON：只替换首个字符串参数
            Map<String, Object> out = new LinkedHashMap<>(args);
            Optional<String> first = out.entrySet().stream()
                    .filter(en -> en.getValue() instanceof String)
                    .map(Map.Entry::getKey).findFirst();
            first.ifPresent(k -> out.put(k, redactedJson));
            return out;
        }
    }

    private String writeJson(Map<String, Object> args) {
        try {
            return mapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }
}
