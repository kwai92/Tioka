package com.tioka.runtime.kernel.application.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.spec.FallbackStep;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.agent.api.spec.OutputMode;
import com.tioka.model.api.constraint.StructuredDemand;
import com.tioka.ontology.api.schema.DataItemOutcome;
import com.tioka.ontology.api.schema.FallbackDeclaration;
import com.tioka.ontology.api.schema.ProcessedOutput;
import com.tioka.ontology.api.schema.RawModelOutput;
import com.tioka.ontology.api.schema.ReasonCode;
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
 * 输出阶段（SCH-02/SCH-03/SCH-09，M3-03 §5 + ADR-066 §5/§6/§7）。Turn 结束前：
 * 校验（全部错误不首错停止）→ 证据绑定（requireEvidence）→ 修复循环 → 降级阶梯 → 写 {@code data} item。
 *
 * <p>修复语义（ADR-066 §6）：{@code maxRepair}（缺省 2）+ {@code maxRepairTokens}
 * （首次输出 token 的 2 倍，先到为准）+ 早停（错误集合与上一轮完全相同立即停）+
 * 修复轮同方言同生成方式 + 只给错误不给答案。按原因码分类：截断不按格式错误修复、
 * 拒答/内容过滤不修复直接进降级并写 {@code guardrail.checked}、空输出原样重试一次。
 *
 * <p>降级阶梯（ADR-066 §7.1，SCH-09）：{@code OutputContract.fallback} 有序步骤
 * {@code REPAIR} / {@code SWITCH_MODE} / {@code SWITCH_MODEL} / {@code FALLBACK_VALUE}
 * / {@code HUMAN} / {@code FAIL}；执行足迹写入 {@code producedBy.fallbackSteps}。
 * 末步须为终局步骤（发布校验）。{@code SWITCH_MODE}/{@code SWITCH_MODEL}/{@code DECOMPOSE}
 * 在 M3 无对应杠杆（无更严生成方式 / 无候选切换 / 归 SCH-12），按「跳过」记录。
 *
 * <p>兜底值（ADR-066 §7.3）：仅 Schema 用 {@code x-tioka-fallback} 声明且其余字段全通过时替换，
 * 结局 {@code VALID_WITH_UNKNOWN} + {@code unknownPaths}。
 *
 * <p>人工补录（ADR-066 §7.4）：{@link LoopPorts.HumanTaskPort} 生成 {@code data.complete} 任务，
 * 结局 {@code HUMAN_PENDING}，Turn 挂起；未接线时降级为 {@code FAILED}。
 *
 * <p>{@code mode=TOOL}：{@code emit_output} 作为普通工具，每次调用做同样校验，
 * 失败以 tool_result 交回模型自修；Turn 正常退出时汇总，有效者为最终输出（M3-03 L345）。
 *
 * <p>无 {@code output} 段的 AgentSpec：本类直通，行为与现状完全一致。
 */
public class OutputStage {

    private static final Logger log = LoggerFactory.getLogger(OutputStage.class);

    /** M3-03 §5：TOOL 模式的提交工具名（与 StructuredRequestBuilder.emit_output 一致）。 */
    public static final String EMIT_OUTPUT = "emit_output";

    /** 修复用尽的原因码（复用 runtime_turn.error_code；ADR-037 第 5 步）。 */
    public static final String OUTPUT_INVALID = "output_invalid";

    private final ObjectMapper mapper;
    /** agentVersionId → OutputContract；null = 自由文本 Agent（直通）。 */
    private final Function<String, OutputContract> contracts;
    /** SchemaApi；null（tioka.ontology.schema.enabled=false）→ OutputStage 直通。 */
    private final SchemaApi schemas;
    private final LoopPorts.ModelPort modelPort;
    private final ContextBuilder contextBuilder;
    private final Function<String, List<RuntimeItemDO>> itemLoader;
    private final LoopPorts.ItemSink sink;
    /** SCH-09 §7.4：人工补录任务端口；null = 未接线（HUMAN 步骤降级为 FAILED）。 */
    private final LoopPorts.HumanTaskPort humanTasks;

    /** TOOL 模式：本 Turn 已记录的 emit_output 调用（valid 的评估与最后的错误集）。 */
    private static final class EmitLedger {
        Evaluation lastValid;
        List<SchemaError> lastErrors = List.of();
        int calls;
        int repairRounds;
    }

    /** FINAL 模式降级阶梯的执行态。 */
    private static final class RunState {
        RawModelOutput raw;
        String modelRef;
        int maxRepairTokens;
        int repairTokens;
        int repairRounds;
        int promptTokens;
        int completionTokens;
        final List<String> fallbackSteps = new ArrayList<>();
    }

    private final Map<String, EmitLedger> ledgers = new LinkedHashMap<>();

    public OutputStage(ObjectMapper mapper,
                       Function<String, OutputContract> contracts,
                       SchemaApi schemas,
                       LoopPorts.ModelPort modelPort,
                       ContextBuilder contextBuilder,
                       Function<String, List<RuntimeItemDO>> itemLoader,
                       LoopPorts.ItemSink sink) {
        this(mapper, contracts, schemas, modelPort, contextBuilder, itemLoader, sink, null);
    }

    public OutputStage(ObjectMapper mapper,
                       Function<String, OutputContract> contracts,
                       SchemaApi schemas,
                       LoopPorts.ModelPort modelPort,
                       ContextBuilder contextBuilder,
                       Function<String, List<RuntimeItemDO>> itemLoader,
                       LoopPorts.ItemSink sink,
                       LoopPorts.HumanTaskPort humanTasks) {
        this.mapper = mapper;
        this.contracts = contracts;
        this.schemas = schemas;
        this.modelPort = modelPort;
        this.contextBuilder = contextBuilder;
        this.itemLoader = itemLoader;
        this.sink = sink;
        this.humanTasks = humanTasks;
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

    /**
     * SCH-09 §7.4：Turn 从 WAITING 续跑时消费已提交的人工补录结果。命中则按第 1〜8 步校验该
     * 提交数据：通过写 {@code outcome=VALID} 的 data item（{@code producedBy.human = 提交人}）
     * 并收口；不通过则按 Schema 重新生成补录任务（人也会填错），Turn 再次挂起。
     *
     * @return {@code null} = 无待续跑补录任务，调用方走常规循环
     */
    public LoopResult resumePending(LoopContext ctx, int step) {
        if (humanTasks == null) {
            return null;
        }
        // 先判契约：非结构化输出 Agent 直接跳过，避免每次 Turn 都查补录任务表
        OutputContract contract = contractOf(ctx);
        if (contract == null || schemas == null) {
            return null;
        }
        LoopPorts.HumanTaskPort.SubmittedCompletion sub = humanTasks.takeSubmitted(ctx);
        if (sub == null) {
            return null;
        }
        Evaluation ev = evaluate(contract, RawModelOutput.of(sub.answerJson()), visibleDocIds(ctx));
        if (ev.deliverable()) {
            writeDataItem(ctx, step, contract, ev, null, 0, List.of(), null, sub.submittedBy());
            return LoopResult.completed(0, 0);
        }
        // 人也会填错：按原错误清单与候选重新生成补录任务，Turn 再次挂起
        String taskId = createHumanTask(ctx, step, contract, ev);
        if (taskId != null) {
            Evaluation pending = new Evaluation(null, DataItemOutcome.HUMAN_PENDING,
                    ev.reason(), ev.errors(), ev.unknownPaths(), ev.normalizations(), ev.lastCandidate());
            writeDataItem(ctx, step, contract, pending, null, 0, List.of(), taskId);
            return LoopResult.awaitingHuman(taskId, 0, 0);
        }
        writeDataItem(ctx, step, contract, ev, null, 0);
        return LoopResult.completedWithReason(OUTPUT_INVALID, 0, 0);
    }

    // ---- mode=FINAL：修复分类 + 降级阶梯（ADR-066 §6/§7） ----

    private LoopResult finalizeFinalMode(LoopContext ctx, int step, OutputContract contract,
                                         LoopPorts.ModelPort.Result first, int promptTokens, int completionTokens) {
        List<String> visibleDocIds = visibleDocIds(ctx);
        RunState st = new RunState();
        st.raw = rawOf(first);
        st.modelRef = first == null ? null : first.modelRef();
        st.maxRepairTokens = 2 * Math.max(first == null ? 0 : first.completionTokens(), 0);
        st.promptTokens = promptTokens;
        st.completionTokens = completionTokens;

        Evaluation ev = evaluate(contract, st.raw, visibleDocIds);
        for (FallbackStep fb : contract.fallback()) {
            if (ev.deliverable()) {
                break;
            }
            switch (fb) {
                case REPAIR -> ev = repair(ctx, step, contract, visibleDocIds, st, ev);
                case SWITCH_MODE -> st.fallbackSteps.add("switch_mode:skipped");
                case SWITCH_MODEL -> st.fallbackSteps.add("switch_model:no_eligible_model");
                case DECOMPOSE -> st.fallbackSteps.add("decompose:deferred");
                case FALLBACK_VALUE -> ev = applyFallbackValues(contract, ev, visibleDocIds, st);
                case HUMAN -> {
                    String taskId = createHumanTask(ctx, step, contract, ev);
                    if (taskId != null) {
                        st.fallbackSteps.add("human");
                        Evaluation pending = new Evaluation(null, DataItemOutcome.HUMAN_PENDING,
                                ev.reason(), ev.errors(), ev.unknownPaths(), ev.normalizations(), ev.lastCandidate());
                        writeDataItem(ctx, step, contract, pending, st.modelRef, st.repairRounds,
                                st.fallbackSteps, taskId);
                        return LoopResult.awaitingHuman(taskId, st.promptTokens, st.completionTokens);
                    }
                    st.fallbackSteps.add("human:unavailable");
                }
                case FAIL -> st.fallbackSteps.add("fail");
            }
            progress(ctx, step, fb);
        }
        writeDataItem(ctx, step, contract, ev, st.modelRef, st.repairRounds, st.fallbackSteps, null);
        return ev.deliverable()
                ? LoopResult.completed(st.promptTokens, st.completionTokens)
                : LoopResult.completedWithReason(OUTPUT_INVALID, st.promptTokens, st.completionTokens);
    }

    /**
     * 修复步骤（ADR-066 §6）：按原因码分类——截断不按格式错误修复、拒答/内容过滤不修复直接进降级、
     * 空输出原样重试一次、其余把候选输出与错误清单交回模型；受 {@code maxRepair} + token 预算 +
     * 早停约束，同方言同生成方式（同 {@link StructuredDemand}），只给错误不给答案。
     */
    private Evaluation repair(LoopContext ctx, int step, OutputContract contract,
                              List<String> visibleDocIds, RunState st, Evaluation ev) {
        ReasonCode reason = ev.reason();
        if (reason == ReasonCode.TRUNCATED) {
            // 截断不能靠重生成格式修好；无「提输出上限一档」杠杆 → 直接进降级下一档
            st.fallbackSteps.add("repair:skipped_truncated");
            return ev;
        }
        if (reason == ReasonCode.REFUSED || reason == ReasonCode.CONTENT_FILTERED) {
            // 不修复不追问；写 guardrail.checked 供审计，直接进降级下一步（换模型）
            st.fallbackSteps.add("repair:skipped_" + reason.name().toLowerCase());
            writeGuardrailChecked(ctx, step, reason);
            return ev;
        }
        boolean plainRetry = reason == ReasonCode.EMPTY;
        int maxRounds = plainRetry ? Math.min(1, contract.maxRepair()) : contract.maxRepair();
        List<SchemaError> errs = ev.errors();
        for (int round = 0; round < maxRounds; round++) {
            if (!plainRetry) {
                appendRepairMessage(ctx, step, errs);
            }
            List<Map<String, Object>> messages = contextBuilder.build(itemLoader.apply(ctx.threadId()));
            LoopPorts.ModelPort.Result r = modelPort.chat(ctx, messages, List.of(), delta -> {},
                    structuredDemand(ctx));
            st.promptTokens += r.promptTokens();
            st.completionTokens += r.completionTokens();
            st.repairTokens += r.completionTokens();
            st.repairRounds++;
            st.raw = rawOf(r);
            st.modelRef = r.modelRef();
            Evaluation next = evaluate(contract, st.raw, visibleDocIds);
            if (next.deliverable()) {
                return next;
            }
            List<SchemaError> nextErrs = next.errors();
            if (sameErrors(nextErrs, errs)) {
                st.fallbackSteps.add("repair:early_stop");
                return next;
            }
            errs = nextErrs;
            ev = next;
            if (st.repairTokens >= st.maxRepairTokens) {
                st.fallbackSteps.add("repair:token_budget");
                return next;
            }
        }
        st.fallbackSteps.add(maxRounds == 0 ? "repair:disabled" : "repair:exhausted");
        return ev;
    }

    /**
     * 兜底值步骤（ADR-066 §7.3）：仅当「其余字段全通过、只有兜底字段有错」（全部错误都被
     * {@code x-tioka-fallback} 声明覆盖）时，把候选对象上这些字段替换为 Schema 声明的兜底值，
     * 重跑第 5〜8 步；通过则结局 {@code VALID_WITH_UNKNOWN} + {@code unknownPaths}。
     * 平台永不填「看起来合理」的默认值——只取 Schema 声明的未知/待复核状态。
     */
    private Evaluation applyFallbackValues(OutputContract contract, Evaluation ev,
                                           List<String> visibleDocIds, RunState st) {
        JsonNode candidate = ev.lastCandidate();
        if (candidate == null || !candidate.isObject()) {
            st.fallbackSteps.add("fallback_value:no_candidate");
            return ev;
        }
        List<FallbackDeclaration> decls = schemas.fallbackDeclarations(contract.schemaRef());
        if (decls == null || decls.isEmpty()) {
            st.fallbackSteps.add("fallback_value:no_declaration");
            return ev;
        }
        List<FallbackDeclaration> used = new ArrayList<>();
        for (SchemaError e : ev.errors()) {
            FallbackDeclaration hit = null;
            for (FallbackDeclaration d : decls) {
                if (d.covers(e.pointer())) {
                    hit = d;
                    break;
                }
            }
            if (hit == null) {
                st.fallbackSteps.add("fallback_value:uncovered");
                return ev;
            }
            if (!used.contains(hit)) {
                used.add(hit);
            }
        }
        JsonNode patched = candidate.deepCopy();
        for (FallbackDeclaration d : used) {
            setPointer(patched, d.pointer(), d.value());
        }
        Evaluation re = complete(contract, patched, List.of(), visibleDocIds);
        if (!re.deliverable()) {
            st.fallbackSteps.add("fallback_value:still_invalid");
            return ev;
        }
        List<String> unknown = new ArrayList<>(re.unknownPaths());
        for (FallbackDeclaration d : used) {
            if (!unknown.contains(d.pointer())) {
                unknown.add(d.pointer());
            }
        }
        st.fallbackSteps.add("fallback_value:applied");
        return new Evaluation(re.payload(), DataItemOutcome.VALID_WITH_UNKNOWN, null, List.of(),
                List.copyOf(unknown), re.normalizations(), null);
    }

    // ---- mode=TOOL：emit_output 校验往返 + 汇总 ----

    /** 单次 emit_output 调用的校验（AgentLoop 拦截后调用）；失败以 tool_result 交回模型。 */
    public LoopPorts.ToolOutcome handleEmit(LoopContext ctx, int step, LoopPorts.ToolCall call) {
        OutputContract contract = contractOf(ctx);
        EmitLedger ledger = ledger(ctx);
        ledger.calls++;
        Evaluation ev;
        if (contract == null || schemas == null) {
            ev = failed(ReasonCode.NO_CONTRACT, "NO_CONTRACT", "output contract unavailable", null);
        } else {
            // 工具入参已是提取后的 JSON（ADR-066 §5 第 2 步的 STRICT_* 口径）
            ev = evaluate(contract, RawModelOutput.of(call.argumentsJson()), visibleDocIds(ctx));
        }
        if (ev.deliverable()) {
            ledger.lastValid = ev;
            ledger.lastErrors = List.of();
            return new LoopPorts.ToolOutcome(call.callId(), call.name(), true, "output accepted", 0L);
        }
        ledger.lastErrors = ev.errors();
        ledger.repairRounds++;
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("ok", false);
        feedback.put("errors", ev.errors().stream()
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
            writeDataItem(ctx, step, contract, ledger.lastValid, null, ledger.calls);
            return LoopResult.completed(promptTokens, completionTokens);
        }
        List<SchemaError> errors = ledger.lastErrors.isEmpty()
                ? List.of(new SchemaError("", "NO_OUTPUT", "Turn 结束前没有有效的 emit_output 提交"))
                : ledger.lastErrors;
        Evaluation ev = new Evaluation(null, DataItemOutcome.FAILED, reasonOf(errors.get(0).code()),
                errors, List.of(), List.of(), null);
        writeDataItem(ctx, step, contract, ev, null, ledger.calls);
        return LoopResult.completedWithReason(OUTPUT_INVALID, promptTokens, completionTokens);
    }

    // ---- 流水线第 1〜8 步 ----

    /**
     * 单次产出的完整评估：第 1〜4 步（完成状态/提取/严格解析/白名单规整）由 ontology 的
     * {@code SchemaApi.process} 承接；第 5〜8 步（Schema 校验/语义规则/证据与引用/领域钩子）
     * 在此执行。任何异常都转为 {@link DataItemOutcome#FAILED} + {@link ReasonCode#INTERNAL}，
     * 不向 Turn 循环传播（ADR-066 §1.3）。
     */
    private Evaluation evaluate(OutputContract contract, RawModelOutput raw, List<String> visibleDocIds) {
        try {
            ProcessedOutput processed = schemas.process(contract.schemaRef(), raw);
            if (processed == null) {
                // 未接入流水线的 SchemaApi 实现：回退旧解析路径（无第 1〜4 步语义）
                JsonNode payload = parsePayload(raw == null ? null : raw.text());
                return payload == null
                        ? failed(ReasonCode.NO_JSON, "NO_JSON", "输出不是单个合法 JSON 对象", null)
                        : complete(contract, payload, List.of(), visibleDocIds);
            }
            if (!processed.ok()) {
                return new Evaluation(null, DataItemOutcome.FAILED, processed.failure(), processed.errors(),
                        List.of(), List.of(), processed.lastCandidate());
            }
            return complete(contract, processed.payload(), processed.normalizations(), visibleDocIds);
        } catch (RuntimeException e) {
            log.warn("输出流水线内部异常 ref={}：{}", contract.schemaRef(), e.toString());
            return failed(ReasonCode.INTERNAL, "INTERNAL", "输出流水线内部异常：" + e.getMessage(), null);
        }
    }

    /** 第 5〜8 步：权威校验 → 语义规则（ERROR 阻断 / WARN 转 {@code VALID_WITH_UNKNOWN}）。 */
    private Evaluation complete(OutputContract contract, JsonNode payload, List<String> normalizations,
                                List<String> visibleDocIds) {
        List<SchemaError> errors = validate(contract, payload, visibleDocIds);
        if (!errors.isEmpty()) {
            return new Evaluation(null, DataItemOutcome.FAILED, reasonOf(errors.get(0).code()), errors,
                    List.of(), normalizations, payload);
        }
        List<String> unknownPaths = schemas.warnPaths(contract.schemaRef(), payload);
        if (unknownPaths == null) {
            unknownPaths = List.of();
        }
        DataItemOutcome outcome = unknownPaths.isEmpty()
                ? DataItemOutcome.VALID : DataItemOutcome.VALID_WITH_UNKNOWN;
        return new Evaluation(payload, outcome, null, List.of(), unknownPaths, normalizations, null);
    }

    private Evaluation failed(ReasonCode reason, String code, String message, JsonNode lastCandidate) {
        return new Evaluation(null, DataItemOutcome.FAILED, reason,
                List.of(new SchemaError("", code, message)), List.of(), List.of(), lastCandidate);
    }

    /** 单次产出评估（ADR-066 §5 第 1〜8 步的结果）。 */
    private record Evaluation(JsonNode payload, DataItemOutcome outcome, ReasonCode reason,
                              List<SchemaError> errors, List<String> unknownPaths,
                              List<String> normalizations, JsonNode lastCandidate) {

        boolean deliverable() {
            return outcome == DataItemOutcome.VALID || outcome == DataItemOutcome.VALID_WITH_UNKNOWN;
        }
    }

    /** 第 1 步完成状态信号（ADR-066 §5）：由供应商 finishReason 映射。 */
    static RawModelOutput rawOf(LoopPorts.ModelPort.Result r) {
        if (r == null) {
            return RawModelOutput.of(null);
        }
        String fr = r.finishReason();
        boolean truncated = fr != null
                && ("length".equalsIgnoreCase(fr) || "max_tokens".equalsIgnoreCase(fr));
        boolean filtered = fr != null && "content_filter".equalsIgnoreCase(fr);
        return new RawModelOutput(r.text(), truncated, r.refused(), filtered);
    }

    /** {@link SchemaError#code()} → 原因码（ADR-066 §1.2）。 */
    static ReasonCode reasonOf(String code) {
        if (code == null || code.isBlank()) {
            return ReasonCode.INTERNAL;
        }
        return switch (code) {
            case "SCHEMA_VIOLATION", "SCHEMA", "SCHEMA_NOT_FOUND" -> ReasonCode.SCHEMA;
            case "RULE" -> ReasonCode.RULE;
            case "RULE_TIMEOUT" -> ReasonCode.RULE_TIMEOUT;
            case "EVIDENCE" -> ReasonCode.EVIDENCE;
            case "QUOTE_MISMATCH" -> ReasonCode.QUOTE_MISMATCH;
            case "PATH_NOT_FOUND" -> ReasonCode.PATH_NOT_FOUND;
            case "REF_NOT_FOUND" -> ReasonCode.REF_NOT_FOUND;
            case "UNKNOWN_PLACEHOLDER" -> ReasonCode.UNKNOWN_PLACEHOLDER;
            case "HOOK" -> ReasonCode.HOOK;
            case "NO_JSON" -> ReasonCode.NO_JSON;
            case "MULTIPLE_JSON" -> ReasonCode.MULTIPLE_JSON;
            case "SYNTAX" -> ReasonCode.SYNTAX;
            case "NO_CONTRACT" -> ReasonCode.NO_CONTRACT;
            case "NO_OUTPUT" -> ReasonCode.NO_OUTPUT;
            default -> ReasonCode.INTERNAL;
        };
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
        // ADR-066 §5 第 6 步：语义规则 x-tioka-rules
        errors.addAll(schemas.checkRules(contract.schemaRef(), payload));
        if (Boolean.TRUE.equals(contract.requireEvidence())) {
            errors.addAll(schemas.checkEvidence(contract.schemaRef(), payload, visibleDocIds));
        }
        // ADR-066 §5 第 7 步：引用完整性 x-tioka-ref
        errors.addAll(schemas.checkRefs(contract.schemaRef(), payload, visibleDocIds));
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

    /**
     * 写 {@code data} item（ADR-066 §1.1）：{@code outcome} 四结局、{@code failure} 原因码、
     * {@code unknownPaths}（兜底/领域 WARN）、{@code producedBy}（含降级足迹）；{@code FAILED}
     * 时不交付部分对象，最后一次候选输出另存为 {@code lastCandidate}（不可消费）。
     */
    private void writeDataItem(LoopContext ctx, int step, OutputContract contract, Evaluation ev,
                               String modelRef, int repairRounds) {
        writeDataItem(ctx, step, contract, ev, modelRef, repairRounds, List.of(), null);
    }

    private void writeDataItem(LoopContext ctx, int step, OutputContract contract, Evaluation ev,
                               String modelRef, int repairRounds, List<String> fallbackSteps,
                               String humanTaskId) {
        writeDataItem(ctx, step, contract, ev, modelRef, repairRounds, fallbackSteps, humanTaskId, null);
    }

    private void writeDataItem(LoopContext ctx, int step, OutputContract contract, Evaluation ev,
                               String modelRef, int repairRounds, List<String> fallbackSteps,
                               String humanTaskId, String producedByHuman) {
        boolean valid = ev.deliverable();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "data");
        content.put("schemaRef", contract.schemaRef());
        content.put("valid", valid);
        content.put("outcome", ev.outcome().name());
        content.put("reasonCode", ev.reason() == null ? null : ev.reason().name());
        content.put("errors", ev.errors().stream()
                .map(e -> Map.of("pointer", e.pointer(), "code", e.code(), "message", e.message()))
                .toList());
        content.put("payload", valid ? ev.payload() : null);
        content.put("unknownPaths", ev.unknownPaths());
        content.put("repairRounds", repairRounds);
        if (humanTaskId != null) {
            content.put("humanTaskId", humanTaskId);
        }
        Map<String, Object> producedBy = new LinkedHashMap<>();
        producedBy.put("model", modelRef);
        // SCH-10（ADR-066 §10.1）：生成方式维度（OutputMode：FINAL / TOOL）随 data item 落库
        producedBy.put("mode", contract.mode() == null ? null : contract.mode().name());
        producedBy.put("repairRounds", repairRounds);
        if (fallbackSteps != null && !fallbackSteps.isEmpty()) {
            producedBy.put("fallbackSteps", fallbackSteps);
        }
        if (producedByHuman != null) {
            producedBy.put("human", producedByHuman);
        }
        if (!ev.normalizations().isEmpty()) {
            producedBy.put("normalizations", ev.normalizations());
        }
        content.put("producedBy", producedBy);
        if (!valid && ev.lastCandidate() != null) {
            content.put("lastCandidate", ev.lastCandidate());
        }
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

    /** ADR-066 §7.1：每步发进度事件（复用 status item）。 */
    private void progress(LoopContext ctx, int step, FallbackStep fb) {
        sink.status(ctx, step, "output.fallback." + fb.name().toLowerCase());
    }

    /** ADR-066 §6：拒答/内容过滤写 {@code guardrail.checked}（FLAG）供审计。 */
    private void writeGuardrailChecked(LoopContext ctx, int step, ReasonCode reason) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "guardrail.checked");
        content.put("flag", true);
        content.put("reasonCode", reason.name());
        sink.complete(ctx, step, ItemType.STATUS.wire(), "system", json(content));
    }

    /** SCH-09 §7.4：生成人工补录任务；未接线/失败返回 null（降级为 FAILED）。 */
    private String createHumanTask(LoopContext ctx, int step, OutputContract contract, Evaluation ev) {
        if (humanTasks == null) {
            return null;
        }
        String candidateJson = ev.lastCandidate() == null || ev.lastCandidate().isNull()
                ? null : json(ev.lastCandidate());
        List<String> pointers = ev.errors().stream().map(SchemaError::pointer).distinct().toList();
        try {
            return humanTasks.create(ctx, step, contract.schemaRef(), candidateJson, pointers);
        } catch (RuntimeException e) {
            log.warn("创建人工补录任务失败 ref={}：{}", contract.schemaRef(), e.toString());
            return null;
        }
    }

    /** 按 JSON Pointer 写入值（缺失的中间对象按需创建）；仅支持对象路径。 */
    static void setPointer(JsonNode root, String pointer, JsonNode value) {
        if (root == null || !root.isObject() || pointer == null || pointer.isBlank()) {
            return;
        }
        String[] parts = pointer.split("/");
        if (parts.length < 2) {
            return;
        }
        JsonNode cur = root;
        for (int i = 1; i < parts.length - 1; i++) {
            String key = unescapePointer(parts[i]);
            JsonNode child = cur.get(key);
            if (child == null || !child.isObject()) {
                child = ((ObjectNode) cur).putObject(key);
            }
            cur = child;
        }
        ((ObjectNode) cur).set(unescapePointer(parts[parts.length - 1]), value);
    }

    private static String unescapePointer(String token) {
        return token.replace("~1", "/").replace("~0", "~");
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
