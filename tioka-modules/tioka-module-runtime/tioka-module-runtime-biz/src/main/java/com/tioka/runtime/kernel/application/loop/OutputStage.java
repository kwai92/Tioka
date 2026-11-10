package com.tioka.runtime.kernel.application.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.agent.api.spec.OutputMode;
import com.tioka.model.api.constraint.StructuredDemand;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.api.schema.SchemaError;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.kernel.domain.ContextBuilder;
import com.tioka.runtime.kernel.domain.LoopContext;
import com.tioka.runtime.kernel.domain.LoopPorts;
import com.tioka.runtime.kernel.domain.LoopResult;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 输出阶段（SCH-02/SCH-03，M3-03 §5 + ADR-066 §5/§6）。Turn 结束前：
 * 校验（全部错误不首错停止）→ 证据绑定（requireEvidence）→ 修复循环 → 写 {@code data} item。
 *
 * <p>修复语义（ADR-066 §6）：{@code maxRepair}（缺省 2）+ {@code maxRepairTokens}
 * （首次输出 token 的 2 倍，先到为准）+ 早停（错误集合与上一轮完全相同立即停）+
 * 修复轮同方言同生成方式 + 只给错误不给答案。修复用尽 → data item(valid=false) 且
 * Turn {@code COMPLETED(reason=output_invalid)}（复用 error_code 列，不新增 TurnStatus）。
 *
 * <p>{@code mode=TOOL}：{@code emit_output} 作为普通工具，每次调用做同样校验，
 * 失败以 tool_result 交回模型自修；Turn 正常退出时汇总，有效者为最终输出（M3-03 L345）。
 *
 * <p>无 {@code output} 段的 AgentSpec：本类直通，行为与现状完全一致。
 *
 * <p>visibleDocIds 最小来源（SCH-03）：扫描本 Turn 的 {@code doc.unit} 工具调用，
 * 从 unitId（形如 {@code {docId}:{pageNo}:{seq}}）提取可见文档清单；
 * 完整方案（space 级 ACL 预授权等）归 SCH-06+。
 */
public class OutputStage {

    private static final Logger log = LoggerFactory.getLogger(OutputStage.class);

    /** M3-03 §5：TOOL 模式的提交工具名（与 StructuredRequestBuilder.emit_output 一致）。 */
    public static final String EMIT_OUTPUT = "emit_output";

    /** 修复用尽的原因码（复用 runtime_turn.error_code；ADR-037 第 5 步）。 */
    public static final String OUTPUT_INVALID = "output_invalid";

    private static final Set<String> CITATION_FIELDS = Set.of("evidence", "sources");

    private final ObjectMapper mapper;
    /** agentVersionId → OutputContract；null = 自由文本 Agent（直通）。 */
    private final Function<String, OutputContract> contracts;
    /** SchemaApi；null（tioka.ontology.schema.enabled=false）→ OutputStage 直通。 */
    private final SchemaApi schemas;
    private final LoopPorts.ModelPort modelPort;
    private final ContextBuilder contextBuilder;
    private final Function<String, List<RuntimeItemDO>> itemLoader;
    private final LoopPorts.ItemSink sink;

    /** TOOL 模式：本 Turn 已记录的 emit_output 调用（valid 的 payload 与最后的错误集）。 */
    private static final class EmitLedger {
        JsonNode lastValid;
        List<SchemaError> lastErrors = List.of();
        int calls;
        int repairRounds;
    }

    private final Map<String, EmitLedger> ledgers = new LinkedHashMap<>();

    public OutputStage(ObjectMapper mapper,
                       Function<String, OutputContract> contracts,
                       SchemaApi schemas,
                       LoopPorts.ModelPort modelPort,
                       ContextBuilder contextBuilder,
                       Function<String, List<RuntimeItemDO>> itemLoader,
                       LoopPorts.ItemSink sink) {
        this.mapper = mapper;
        this.contracts = contracts;
        this.schemas = schemas;
        this.modelPort = modelPort;
        this.contextBuilder = contextBuilder;
        this.itemLoader = itemLoader;
        this.sink = sink;
    }

    /** TOOL 模式且契约/校验通路可用时，把 emit_output 约束交给 Model Hub（toolRequest 形态）。 */
    public StructuredDemand structuredDemand(LoopContext ctx) {
        OutputContract contract = contractOf(ctx);
        if (contract == null || schemas == null) {
            return null;
        }
        JsonNode schema = loadSchema(contract);
        if (schema == null) {
            return null;
        }
        return new StructuredDemand(contract.schemaRef(), schema, contract.mode() == OutputMode.TOOL);
    }

    public boolean toolMode(LoopContext ctx) {
        OutputContract contract = contractOf(ctx);
        return contract != null && schemas != null
                && contract.mode() == OutputMode.TOOL
                && loadSchema(contract) != null;
    }

    /**
     * Turn 正常退出点（AgentLoop「无 toolCalls → COMPLETED」分支）。
     *
     * @return null = OutputStage 不适用（无契约 / 校验通路缺失），调用方走原路径
     */
    public LoopResult onLoopExit(LoopContext ctx, int step,
                                 LoopPorts.ModelPort.Result last, int promptTokens, int completionTokens) {
        OutputContract contract = contractOf(ctx);
        if (contract == null || schemas == null) {
            return null;
        }
        JsonNode schema = loadSchema(contract);
        if (schema == null) {
            log.warn("OutputStage 跳过：schema 未登记 ref={}", contract.schemaRef());
            return null;
        }
        return contract.mode() == OutputMode.TOOL
                ? finalizeToolMode(ctx, step, contract, promptTokens, completionTokens)
                : finalizeFinalMode(ctx, step, contract, last, promptTokens, completionTokens);
    }

    // ---- mode=FINAL：修复循环（ADR-066 §6） ----

    private LoopResult finalizeFinalMode(LoopContext ctx, int step, OutputContract contract,
                                         LoopPorts.ModelPort.Result first, int promptTokens, int completionTokens) {
        List<String> visibleDocIds = visibleDocIds(ctx);
        JsonNode payload = parsePayload(first == null ? null : first.text());
        int maxRepairTokens = 2 * Math.max(first == null ? 0 : first.completionTokens(), 0);
        int repairTokens = 0;
        List<SchemaError> prevErrors = null;
        for (int round = 0; ; round++) {
            List<SchemaError> errs = validate(contract, payload, visibleDocIds);
            if (errs.isEmpty()) {
                writeDataItem(ctx, step, contract, payload, List.of(), true, round);
                return LoopResult.completed(promptTokens, completionTokens);
            }
            // 早停：错误集合与上一轮完全相同 → 模型修不好，立即停止
            if (prevErrors != null && sameErrors(errs, prevErrors)) {
                writeDataItem(ctx, step, contract, payload, errs, false, round);
                return LoopResult.completedWithReason(OUTPUT_INVALID, promptTokens, completionTokens);
            }
            // 先到为准：轮数用尽或修复 token 预算用尽
            if (round == contract.maxRepair() || repairTokens >= maxRepairTokens) {
                writeDataItem(ctx, step, contract, payload, errs, false, round);
                return LoopResult.completedWithReason(OUTPUT_INVALID, promptTokens, completionTokens);
            }
            // 修复轮：只给错误不给答案，同方言同生成方式（同 StructuredDemand）
            appendRepairMessage(ctx, step, errs);
            List<Map<String, Object>> messages = contextBuilder.build(itemLoader.apply(ctx.threadId()));
            LoopPorts.ModelPort.Result r = modelPort.chat(ctx, messages, List.of(), delta -> {},
                    structuredDemand(ctx));
            promptTokens += r.promptTokens();
            completionTokens += r.completionTokens();
            repairTokens += r.completionTokens();
            payload = parsePayload(r.text());
            prevErrors = errs;
        }
    }

    // ---- mode=TOOL：emit_output 校验往返 + 汇总 ----

    /** 单次 emit_output 调用的校验（AgentLoop 拦截后调用）；失败以 tool_result 交回模型。 */
    public LoopPorts.ToolOutcome handleEmit(LoopContext ctx, int step, LoopPorts.ToolCall call) {
        OutputContract contract = contractOf(ctx);
        EmitLedger ledger = ledger(ctx);
        ledger.calls++;
        String okOutput;
        List<SchemaError> errors;
        if (contract == null || schemas == null) {
            errors = List.of(new SchemaError("", "NO_CONTRACT", "output contract unavailable"));
            okOutput = null;
        } else {
            JsonNode payload = parsePayload(call.argumentsJson());
            errors = validate(contract, payload, visibleDocIds(ctx));
            okOutput = errors.isEmpty() ? call.argumentsJson() : null;
        }
        if (okOutput != null) {
            try {
                ledger.lastValid = mapper.readTree(call.argumentsJson());
            } catch (Exception e) {
                ledger.lastValid = null;
            }
            ledger.lastErrors = List.of();
            return new LoopPorts.ToolOutcome(call.callId(), call.name(), true,
                    "output accepted", 0L);
        }
        ledger.lastErrors = errors;
        ledger.repairRounds++;
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("ok", false);
        feedback.put("errors", errors.stream()
                .map(e -> Map.of("pointer", e.pointer(), "code", e.code(), "message", e.message()))
                .toList());
        // 只给错误不给答案
        return new LoopPorts.ToolOutcome(call.callId(), call.name(), false,
                json(feedback), 0L);
    }

    private LoopResult finalizeToolMode(LoopContext ctx, int step, OutputContract contract,
                                        int promptTokens, int completionTokens) {
        EmitLedger ledger = ledgers.remove(ctx.turnId());
        if (ledger == null) {
            ledger = new EmitLedger();
        }
        if (ledger.lastValid != null) {
            // 汇总：有效者为最终输出（M3-03 L345）
            writeDataItem(ctx, step, contract, ledger.lastValid, List.of(), true, ledger.calls);
            return LoopResult.completed(promptTokens, completionTokens);
        }
        List<SchemaError> errors = ledger.lastErrors.isEmpty()
                ? List.of(new SchemaError("", "NO_OUTPUT", "Turn 结束前没有有效的 emit_output 提交"))
                : ledger.lastErrors;
        writeDataItem(ctx, step, contract, null, errors, false, ledger.calls);
        return LoopResult.completedWithReason(OUTPUT_INVALID, promptTokens, completionTokens);
    }

    // ---- 校验与证据 ----

    private List<SchemaError> validate(OutputContract contract, JsonNode payload,
                                       List<String> visibleDocIds) {
        List<SchemaError> errors = new ArrayList<>();
        if (payload == null) {
            // ADR-066 §5 第 2/3 步最小实现：提取/解析失败即校验错误
            errors.add(new SchemaError("", "NO_JSON", "输出不是单个合法 JSON 对象"));
            return errors;
        }
        errors.addAll(schemas.validate(contract.schemaRef(), payload));
        if (Boolean.TRUE.equals(contract.requireEvidence())) {
            errors.addAll(schemas.checkEvidence(contract.schemaRef(), payload, visibleDocIds));
        }
        return errors;
    }

    // ---- visibleDocIds 最小来源 ----

    /**
     * 扫本 Turn 的 {@code doc.unit} 工具调用，从 unitId（{docId}:{pageNo}:{seq}）提取可见文档。
     */
    List<String> visibleDocIds(LoopContext ctx) {
        Set<String> docIds = new LinkedHashSet<>();
        try {
            for (RuntimeItemDO item : itemLoader.apply(ctx.threadId())) {
                if (!ctx.turnId().equals(item.getTurnId())
                        || !ItemType.TOOL_CALL.wire().equals(item.getType())) {
                    continue;
                }
                Map<String, Object> content = readMap(item.getContentJson());
                if (!"doc.unit".equals(String.valueOf(content.get("name")))) {
                    continue;
                }
                Object args = content.get("arguments");
                JsonNode argsNode;
                if (args instanceof String s) {
                    argsNode = mapper.readTree(s);
                } else {
                    argsNode = mapper.valueToTree(args == null ? Map.of() : args);
                }
                String unitId = argsNode.path("unitId").asText(null);
                String docId = docIdOf(unitId);
                if (docId != null) {
                    docIds.add(docId);
                }
            }
        } catch (Exception e) {
            log.warn("visibleDocIds 聚合失败 turn={}：{}", ctx.turnId(), e.toString());
        }
        return List.copyOf(docIds);
    }

    /** unitId 形如 {docId}:{pageNo}:{seq}；与 DocToolProvider 的解析约定一致。 */
    static String docIdOf(String unitId) {
        if (unitId == null || unitId.isBlank()) {
            return null;
        }
        int sep = unitId.lastIndexOf(':');
        if (sep <= 0) {
            return null;
        }
        int docEnd = unitId.lastIndexOf(':', sep - 1);
        if (docEnd <= 0) {
            return null;
        }
        return unitId.substring(0, docEnd);
    }

    // ---- data item ----

    private void writeDataItem(LoopContext ctx, int step, OutputContract contract, JsonNode payload,
                               List<SchemaError> errors, boolean valid, int repairRounds) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("schemaRef", contract.schemaRef());
        content.put("valid", valid);
        content.put("outcome", valid ? "VALID" : "FAILED");
        content.put("errors", errors.stream()
                .map(e -> Map.of("pointer", e.pointer(), "code", e.code(), "message", e.message()))
                .toList());
        content.put("payload", payload == null ? null : payload);
        content.put("repairRounds", repairRounds);
        sink.complete(ctx, step, ItemType.DATA.wire(), "assistant", json(content));
    }

    private void appendRepairMessage(LoopContext ctx, int step, List<SchemaError> errors) {
        // 只给错误不给答案（ADR-066 §6）：schema 模式无工具调用，错误清单以 user 消息承载
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", "上一次输出未通过结构化校验，请输出完整的修正版本（JSON）。错误清单："
                + json(errors.stream()
                        .map(e -> Map.of("pointer", e.pointer(), "code", e.code(), "message", e.message()))
                        .toList()));
        sink.complete(ctx, step, ItemType.MESSAGE.wire(), "user", json(content));
    }

    // ---- 杂项 ----

    private OutputContract contractOf(LoopContext ctx) {
        try {
            return contracts == null ? null : contracts.apply(ctx.agentVersionId());
        } catch (RuntimeException e) {
            log.warn("读取 OutputContract 失败 version={}：{}", ctx.agentVersionId(), e.toString());
            return null;
        }
    }

    private JsonNode loadSchema(OutputContract contract) {
        return schemas.get(contract.schemaRef())
                .map(def -> def.jsonSchema())
                .orElse(null);
    }

    private EmitLedger ledger(LoopContext ctx) {
        return ledgers.computeIfAbsent(ctx.turnId(), k -> new EmitLedger());
    }

    private JsonNode parsePayload(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(text);
            return node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 早停判据：同路径、同原因（ADR-066 §6）。 */
    static boolean sameErrors(List<SchemaError> a, List<SchemaError> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Set<String> sa = new LinkedHashSet<>();
        Set<String> sb = new LinkedHashSet<>();
        for (SchemaError e : a) {
            sa.add(e.pointer() + "|" + e.code() + "|" + e.message());
        }
        for (SchemaError e : b) {
            sb.add(e.pointer() + "|" + e.code() + "|" + e.message());
        }
        return sa.equals(sb);
    }

    private Map<String, Object> readMap(String json) {
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
