package com.tioka.agent.application.publish;

import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.api.spec.FallbackStep;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.model.api.profile.StructuredOutputPlanApi;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.api.schema.SchemaDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SCH-02：{@code output.schemaRef} 必须存在且已 PUBLISHED。
 * SchemaApi 未启用时，若声明了 output 则报 REF_UNAVAILABLE（特性默认关闭，现有 Agent output=null 不受影响）。
 *
 * <p>SCH-06（ADR-066 §2/§3）：在存在性之上追加两项发布期校验：
 * <ol>
 *   <li>严格档：Schema 声明为非严格档（{@code x-tioka-tier: relaxed}）时，须在
 *       {@code output.allowRelaxedSchema} 显式允许，否则拒绝。</li>
 *   <li>候选模型试编译：按 Agent 路由计划枚举候选模型，用权威 Schema 逐个试编译方言，
 *       全部候选都失败则拒绝发布；部分失败者从候选中排除并记日志提示。</li>
 * </ol>
 */
@Component
@Order(25)
public class OutputContractPublishCheck implements PublishCheck {

    private static final Logger log = LoggerFactory.getLogger(OutputContractPublishCheck.class);

    private final ObjectProvider<SchemaApi> schemaApi;
    private final ObjectProvider<StructuredOutputPlanApi> structuredOutputPlan;

    public OutputContractPublishCheck(ObjectProvider<SchemaApi> schemaApi) {
        this(schemaApi, null);
    }

    @Autowired
    public OutputContractPublishCheck(ObjectProvider<SchemaApi> schemaApi,
                                      ObjectProvider<StructuredOutputPlanApi> structuredOutputPlan) {
        this.schemaApi = schemaApi;
        this.structuredOutputPlan = structuredOutputPlan;
    }

    @Override
    public List<PublishError> check(AgentSpec spec, PublishContext ctx) {
        OutputContract output = spec.output();
        if (output == null) {
            return List.of();
        }
        List<PublishError> errors = new ArrayList<>();
        String ref = output.schemaRef();
        if (ref == null || ref.isBlank()) {
            errors.add(new PublishError("/output/schemaRef", "SCHEMA_INVALID", "output.schemaRef 不能为空"));
            return errors;
        }
        SchemaApi api = schemaApi.getIfAvailable();
        if (api == null) {
            errors.add(new PublishError(
                    "/output/schemaRef",
                    "REF_UNAVAILABLE",
                    "SchemaApi 未启用（tioka.ontology.schema.enabled），无法校验 schemaRef: " + ref));
            return errors;
        }
        Optional<SchemaDef> def = api.get(ref);
        if (def.isEmpty()) {
            errors.add(new PublishError("/output/schemaRef", "REF_NOT_FOUND", "Schema 不存在: " + ref));
            return errors;
        }
        if (!"PUBLISHED".equalsIgnoreCase(def.get().status())) {
            errors.add(new PublishError(
                    "/output/schemaRef",
                    "REF_UNAVAILABLE",
                    "Schema 未发布: " + ref + " status=" + def.get().status()));
            return errors;
        }
        checkStrictTier(api, ref, output, errors);
        checkFallbackTerminal(output, errors);
        checkCandidateCompile(spec, def.get().jsonSchema(), ref, errors);
        return errors;
    }

    /**
     * ADR-066 §7.1：降级阶梯列表末步必须是 {@code FALLBACK_VALUE} / {@code HUMAN} / {@code FAIL} 之一，
     * 否则发布被拒（否则阶梯可能走完而无终局）。
     */
    private void checkFallbackTerminal(OutputContract output, List<PublishError> errors) {
        List<FallbackStep> steps = output.fallback();
        if (steps == null || steps.isEmpty()) {
            return;
        }
        FallbackStep last = steps.get(steps.size() - 1);
        if (!last.terminal()) {
            errors.add(new PublishError(
                    "/output/fallback",
                    "FALLBACK_TERMINAL_REQUIRED",
                    "降级阶梯末步必须是 FALLBACK_VALUE / HUMAN / FAIL 之一，当前为: " + last));
        }
    }

    /** ADR-066 §3：非严格档 Schema 仅能被声明了 {@code allowRelaxedSchema} 的 Agent 引用。 */
    private void checkStrictTier(SchemaApi api, String ref, OutputContract output, List<PublishError> errors) {
        if (api.relaxedTier(ref) && !Boolean.TRUE.equals(output.allowRelaxedSchema())) {
            errors.add(new PublishError(
                    "/output/allowRelaxedSchema",
                    "STRICT_TIER_REQUIRED",
                    "Schema 为非严格档（x-tioka-tier: relaxed），须在 output.allowRelaxedSchema 显式允许: " + ref));
        }
    }

    /** ADR-066 §2：按路由计划枚举候选模型逐个试编译；全部失败则发布被拒。 */
    private void checkCandidateCompile(AgentSpec spec, com.fasterxml.jackson.databind.JsonNode schema,
                                       String ref, List<PublishError> errors) {
        StructuredOutputPlanApi planApi = structuredOutputPlan == null
                ? null : structuredOutputPlan.getIfAvailable();
        if (planApi == null || schema == null) {
            return;
        }
        String planId = spec.model() == null ? null : spec.model().routingPlanId();
        // 发布期不区分多模态输入：方言编译的成败与方式无关，取缺省 false 即可。
        List<StructuredOutputPlanApi.CandidateCompile> compiles =
                planApi.trialCompile(planId, schema, false);
        if (compiles.isEmpty()) {
            return;
        }
        List<String> failed = compiles.stream()
                .filter(c -> !c.compiled())
                .map(c -> c.modelRef() + "(" + c.reasonCode() + ")")
                .toList();
        if (failed.isEmpty()) {
            return;
        }
        if (failed.size() == compiles.size()) {
            errors.add(new PublishError(
                    "/output/schemaRef",
                    "DIALECT_UNCOMPILABLE",
                    "路由计划的所有候选模型都无法编译该 Schema: " + ref + " -> " + failed));
            return;
        }
        // 部分候选不可用：从该 Agent 的结构化输出候选中排除并提示（不阻断发布）。
        log.warn("结构化输出候选模型试编译失败，已排除 ref={} 失败候选={}", ref, failed);
    }
}
