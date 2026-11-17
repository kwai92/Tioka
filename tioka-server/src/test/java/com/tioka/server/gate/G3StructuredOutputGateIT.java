package com.tioka.server.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.spec.FallbackStep;
import com.tioka.agent.api.spec.OutputContract;
import com.tioka.agent.api.spec.OutputMode;
import com.tioka.agent.api.spec.OutputStrategy;
import com.tioka.flow.api.dsl.FlowDslDocument;
import com.tioka.flow.api.dsl.FlowPublishContext;
import com.tioka.flow.api.dsl.ValidationResult;
import com.tioka.flow.dsl.DefaultFlowDslValidator;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.GateSeed;
import com.tioka.server.gate.support.LlmStubServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 G3 闸门（SCH-06〜10 转真，ADR-066）：合同 Schema 最终失败率与异常逃逸。
 *
 * <p>规格：M3-00 §8.1 —— 「合同 Schema 最终失败率 ≤ 0.5%、异常逃逸为零、未连 failed 端口的
 * Flow 发布被拒」；ADR-066 §10.3（异常逃逸）/§10.4（闸门表）/§12（SCH-06〜12）；
 * ADR-043「修订：结构化输出的结局端口（2026-11-07）」。
 *
 * <p>环境口径（离线可判定性取舍，逐条登记在设计说明 M3-C29）：
 * <ul>
 *   <li>闸门走真实 Runtime Turn（真实 dispatcher / 真实 ItemSink / 真实落库），只把 LLM 换成
 *       {@link LlmStubServer} 录制回放桩；契约来自已发布 AgentVersion 的 {@code spec.output()},
 *       校验走 {@code tioka.ontology.schema.enabled=true} 打开的真实 {@code SchemaRegistry}。</li>
 *   <li>{@code TRUNCATED / REFUSED / CONTENT_FILTERED} 三条原因码在桩端不可造（桩对非 tool_calls
 *       一律 {@code finish_reason=stop} / {@code tool_calls}，且 {@code Call} 无 {@code refused} 字段），
 *       由 {@code OutputStageIT} 直构 {@code ModelPort.Result} 覆盖；闸门侧只覆盖桩可达的变异集。</li>
 *   <li>「30 份评测合同」语料在仓内不存在（仅规划口径），故失败率用「重复合法载荷的生产类比语料」
 *       承载；该偏离在设计说明登记，最终率值待 CI 回填。</li>
 * </ul>
 *
 * <p>Docker 不可用的本机不判定绿/红：闸门端到端结论归 CI（{@code -Pgate}）。
 */
@Tag("gate")
class G3StructuredOutputGateIT {

    private static final String SCRIPT = "sch-contract";
    private static final String SCHEMA_KEY = "lease.finding";
    private static final String SCHEMA_REF = "lease.finding@1";
    /** 合法载荷（对照）；repeated 生成「生产类比语料」。 */
    private static final String VALID_PAYLOAD = "{\"findingId\":\"F-1\",\"conclusion\":\"SATISFIED\"}";
    private static final int RATE_TURNS = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 步骤 1：合同 Schema 最终失败率 ≤ 0.5% + 异常逃逸为零（SCH-06〜10）。
     *
     * <p>三段：
     * <ol>
     *   <li>生产类比语料（合法载荷）8 个 Turn → 最终失败率 0/8 = 0% ≤ 0.5%；</li>
     *   <li>变异集 9 个 Turn（含合法对照）→ 每输入恰一个 data item、结局必属四结局之一、
     *       无异常逃逸（Turn 不得 FAILED）、交付载荷过独立第二校验器 + 权威校验器；</li>
     *   <li>修复闭环 1 个 Turn（{@code maxRepair=2}）→ 首轮非法、修复轮合法 → 最终 VALID 且
     *       {@code repairRounds ≥ 1}。</li>
     * </ol>
     */
    @Test
    void contractSchemaFinalFailureRateAndMutationEscape() throws Exception {
        try (GateCluster cluster = GateCluster.start(1, SCRIPT,
                Map.of("tioka.ontology.schema.enabled", "true"), false)) {
            ConfigurableApplicationContext ctx = cluster.a();
            JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

            // 发布校验（OutputContractPublishCheck）在 publishSpec 期要求 schemaRef 已 PUBLISHED → 先登记
            publishFindingSchema(ctx);

            // ---------- 1. 生产类比语料：最终失败率 ≤ 0.5% ----------
            // maxRepair=0：每个 Turn 恰好一次模型调用，桩剧本按模循环 → 与 Turn 顺序无关
            GateSeed.Bundle rate = GateSeed.seedPublishedAgent(ctx, cluster.llm().baseUrl(), false,
                    contract(0));
            cluster.llm().putScript(SCRIPT, loopScript(validPayloads()));

            int failures = 0;
            for (int i = 0; i < RATE_TURNS; i++) {
                DataItem item = runTurn(ctx, jdbc, rate, "cm-sch-rate-" + i, "语料第 " + i + " 份合同");
                assertEquals(SCHEMA_REF, item.schemaRef(), "data item 须带权威 Schema 引用");
                assertEquals("FINAL", item.outputMode(), "SCH-10：须落生成方式维度");
                assertNotNull(item.modelRef(), "SCH-10：须落产出模型 ref");
                assertEquals(0, item.repairRounds(), "maxRepair=0 时不得有修复轮");
                if (!"VALID".equals(item.outcome())) {
                    failures++;
                } else {
                    assertDelivered(ctx, item);
                }
            }
            double rateValue = failures / (double) RATE_TURNS;
            System.out.println("[M3-C29 实测] 合同 Schema 最终失败率=" + rateValue
                    + "（" + failures + "/" + RATE_TURNS + "）");
            assertEquals(0, failures, "合法载荷不得出现最终失败，失败率须 ≤ 0.5%："
                    + failures + "/" + RATE_TURNS);

            // ---------- 2. 变异集：零异常逃逸 ----------
            cluster.llm().putScript(SCRIPT, script(mutations()));
            GateSeed.Bundle escape = GateSeed.seedPublishedAgent(ctx, cluster.llm().baseUrl(), false,
                    contract(0));

            List<String> observed = new ArrayList<>();
            for (int i = 0; i < mutations().size(); i++) {
                DataItem item = runTurn(ctx, jdbc, escape, "cm-sch-mut-" + i, "变异输入 " + i);
                String outcome = item.outcome();
                observed.add(outcome);
                assertTrue(List.of("VALID", "VALID_WITH_UNKNOWN", "HUMAN_PENDING", "FAILED").contains(outcome),
                        () -> "结局必属四结局之一，实测=" + outcome);
                // 顺序锚点：桩剧本 loop=false 与 Turn 一一对应，错位会在此暴露
                int idx = i;
                assertEquals(i == 0 ? "VALID" : "FAILED", outcome,
                        () -> "变异 " + idx + " 的期望结局不匹配（桩剧本与 Turn 错位或校验器行为漂移）");
                if ("VALID".equals(outcome)) {
                    assertDelivered(ctx, item);
                } else {
                    // payload 为缺失或 JSON null 均表示「未交付部分对象」。
                    // item.payload() 返回 content.path("payload")，是 Jackson 的 MissingNode/NullNode，
                    // 与 Java null 不等，故不能用 assertNull（否则 NullNode 会被误判为「交付了对象」）。
                    JsonNode failedPayload = item.payload();
                    assertTrue(failedPayload.isMissingNode() || failedPayload.isNull(),
                            "失败不得交付部分对象：" + item.reasonCode() + "，实测 payload=" + failedPayload);
                    assertNotNull(item.reasonCode(), "失败必须带原因码");
                }
            }
            assertTrue(observed.contains("VALID"), "变异集应至少覆盖一次 VALID（否则校验退化为恒失败）");
            assertTrue(observed.contains("FAILED"), "变异集应至少覆盖一次 FAILED（否则校验退化为恒通过）");

            // ---------- 3. 修复闭环：首轮非法 → 修复轮合法 ----------
            GateSeed.Bundle repair = GateSeed.seedPublishedAgent(ctx, cluster.llm().baseUrl(), false,
                    contract(2));
            cluster.llm().putScript(SCRIPT, script(List.of("{\"findingId\":\"F-1\"}", VALID_PAYLOAD)));

            DataItem repaired = runTurn(ctx, jdbc, repair, "cm-sch-repair", "首轮非法后修复");
            assertEquals("VALID", repaired.outcome(), "修复轮合法后结局须为 VALID");
            assertTrue(repaired.repairRounds() >= 1, "须真实发生修复轮，实测=" + repaired.repairRounds());
            assertDelivered(ctx, repaired);
        }
    }

    /**
     * 步骤 2：未连 {@code failed} 端口的 Flow 发布被拒（ADR-043 §3.7 结局端口规则）。
     *
     * <p>口径说明：{@link DefaultFlowDslValidator} 是 ADR-043 规则的唯一实现点，但它**尚未接入**
     * {@code FlowService.publish} 发布路径（生产路径只剩 {@code FlowGraphValidator}，无
     * {@code OUTCOME_FAILED_PORT_MISSING}）——该接线缺口在设计说明登记为偏差/遗留，本用例只对
     * 已实现的规则本身做闸门级断言（正/反/旁路三组），不制造「已拦截」的假象。
     */
    @Test
    void flowPublishRejectedWhenFailedPortMissing() {
        DefaultFlowDslValidator validator = new DefaultFlowDslValidator();

        // 正例：声明了输出契约却未连 failed 端口 → 拒绝
        FlowDslDocument missingPort = baseFlow();
        missingPort.nodes().add(node("se", "transform", Map.of("schema", "order@1")));
        wireThrough(missingPort, "se");
        ValidationResult rejected = validator.validate(missingPort, FlowPublishContext.permissive());
        assertTrue(rejected.hasErrors(), "未连 failed 端口须判为发布错误");
        assertTrue(rejected.hasCode("OUTCOME_FAILED_PORT_MISSING"),
                () -> "须报 OUTCOME_FAILED_PORT_MISSING：" + rejected.issues());
        assertTrue(rejected.errors().stream()
                        .anyMatch(i -> "OUTCOME_FAILED_PORT_MISSING".equals(i.code())),
                "该码须为 ERROR 级（发布拒绝而非告警）");

        // 反例 1：连上 failed 端口 → 放行
        FlowDslDocument wired = baseFlow();
        wired.nodes().add(node("se", "transform", Map.of("schema", "order@1")));
        wired.edges().clear();
        wired.edges().add(edge("in", "out", "se", "in"));
        wired.edges().add(edge("se", "valid", "out", "in"));
        wired.edges().add(edge("se", "failed", "out", "in"));
        assertFalse(validator.validate(wired, FlowPublishContext.permissive())
                .hasCode("OUTCOME_FAILED_PORT_MISSING"), "已连 failed 端口不得再报该码");

        // 反例 2：config 声明 onOutcomeFailure=fail_fast → 放行
        FlowDslDocument failFast = baseFlow();
        failFast.nodes().add(node("se", "transform",
                Map.of("schema", "order@1", "onOutcomeFailure", "fail_fast")));
        wireThrough(failFast, "se");
        assertFalse(validator.validate(failFast, FlowPublishContext.permissive())
                .hasCode("OUTCOME_FAILED_PORT_MISSING"), "fail_fast 旁路不得再报该码");
    }

    // ------------------------------------------------------------------
    // 契约 / Schema
    // ------------------------------------------------------------------

    private static OutputContract contract(int maxRepair) {
        return new OutputContract(SCHEMA_REF, OutputMode.FINAL, maxRepair, false,
                OutputStrategy.DIRECT, null, false, List.of(FallbackStep.REPAIR, FallbackStep.FAIL));
    }

    /** 登记闸门内联最小版合同 Schema（结构与 {@code OutputStageIT.findingSchema} 一致）。 */
    private static void publishFindingSchema(ConfigurableApplicationContext ctx) {
        SchemaApi api = ctx.getBean(SchemaApi.class);
        ((SchemaRegistry) api).publish(SCHEMA_KEY, 1, findingSchema(), "lease");
    }

    private static JsonNode findingSchema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "x-tioka-evidence": "required",
                      "additionalProperties": false,
                      "required": ["findingId", "conclusion"],
                      "properties": {
                        "findingId": {"type": "string"},
                        "conclusion": {"enum": ["SATISFIED", "NOT_SATISFIED"]}
                      }
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // 桩剧本
    // ------------------------------------------------------------------

    private static List<String> validPayloads() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= RATE_TURNS; i++) {
            out.add("{\"findingId\":\"F-" + i + "\",\"conclusion\":\"SATISFIED\"}");
        }
        return out;
    }

    /** 桩可达的变异集（顺序即 Turn 顺序；索引 0 为合法对照）。 */
    private static List<String> mutations() {
        return List.of(
                VALID_PAYLOAD,                                                  // 0 → VALID
                "{\"findingId\":\"F-1\"}",                                      // 1 缺必填
                "{\"findingId\":1,\"conclusion\":\"SATISFIED\"}",               // 2 类型错
                "{\"findingId\":\"F\",\"conclusion\":\"MAYBE\"}",               // 3 枚举外
                "{\"findingId\":\"F\",\"conclusion\":\"SATISFIED\",\"x\":1}",   // 4 额外字段
                "不确定",                                                       // 5 非 JSON
                "{} {}",                                                        // 6 多段 JSON
                "{'a':1}",                                                      // 7 语法错
                ""                                                              // 8 空输出
        );
    }

    private static LlmStubServer.Script script(List<String> contents) {
        return buildScript(contents, false);
    }

    private static LlmStubServer.Script loopScript(List<String> contents) {
        return buildScript(contents, true);
    }

    private static LlmStubServer.Script buildScript(List<String> contents, boolean loop) {
        LlmStubServer.Script script = new LlmStubServer.Script();
        List<LlmStubServer.Call> calls = new ArrayList<>();
        for (String content : contents) {
            LlmStubServer.Call call = new LlmStubServer.Call();
            call.content = content;
            call.usage = usage(12, 8);
            calls.add(call);
        }
        script.calls = calls;
        script.loop = loop ? Boolean.TRUE : null;
        return script;
    }

    private static LlmStubServer.UsageSpec usage(int prompt, int completion) {
        LlmStubServer.UsageSpec u = new LlmStubServer.UsageSpec();
        u.prompt = prompt;
        u.completion = completion;
        return u;
    }

    // ------------------------------------------------------------------
    // Turn / 断言
    // ------------------------------------------------------------------

    private record DataItem(String schemaRef, String outcome, String reasonCode, String modelRef,
                            String outputMode, Integer repairRounds, JsonNode content) {
        JsonNode payload() {
            return content.path("payload");
        }
    }

    /** 跑一个完整 Turn 并取回它的 data item；断言「零逃逸」的两条硬约束。 */
    private static DataItem runTurn(ConfigurableApplicationContext ctx, JdbcTemplate jdbc,
                                    GateSeed.Bundle bundle, String clientMsgId, String text) {
        var thread = GateSeed.createThread(ctx, bundle);
        var handle = GateSeed.startTurn(ctx, bundle, thread.threadId(), clientMsgId, text);
        var done = GateSeed.awaitTerminal(ctx, bundle, handle.turnId(), 60_000);
        assertEquals(TurnStatus.COMPLETED, done.status(),
                () -> "结构化失败也须以 Turn COMPLETED + error_code=output_invalid 收口，不得异常逃逸："
                        + done.status() + "/" + done.errorCode() + "/" + done.errorMessage());
        if (done.errorCode() != null) {
            assertEquals("output_invalid", done.errorCode(),
                    () -> "COMPLETED 的原因码只允许 output_invalid：" + done.errorCode());
        }
        List<DataItem> items = dataItems(jdbc, handle.turnId());
        assertEquals(1, items.size(),
                () -> "每次输入必须恰好落一个 data item（零逃逸），turn=" + handle.turnId()
                        + " 实测=" + items.size());
        return items.get(0);
    }

    /** 交付载荷须同时通过独立第二校验器与权威校验器（二者互为佐证）。 */
    private static void assertDelivered(ConfigurableApplicationContext ctx, DataItem item) {
        JsonNode payload = item.payload();
        assertFalse(payload.isMissingNode() || payload.isNull(), "VALID 必须交付载荷");
        List<String> violations = structuralViolations(findingSchema(), payload);
        assertTrue(violations.isEmpty(), () -> "第二校验器判定不合法：" + violations + " ← " + payload);
        assertTrue(ctx.getBean(SchemaApi.class).validate(SCHEMA_REF, payload).isEmpty(),
                () -> "权威校验器与第二校验器结论不一致 ← " + payload);
    }

    private static List<DataItem> dataItems(JdbcTemplate jdbc, String turnId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT schema_ref, outcome, reason_code, model_ref, output_mode, repair_rounds, content_json "
                        + "FROM runtime_item WHERE turn_id = ? AND type = 'data' ORDER BY seq", turnId);
        List<DataItem> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(new DataItem(
                    (String) r.get("schema_ref"),
                    (String) r.get("outcome"),
                    (String) r.get("reason_code"),
                    (String) r.get("model_ref"),
                    (String) r.get("output_mode"),
                    r.get("repair_rounds") == null ? null : ((Number) r.get("repair_rounds")).intValue(),
                    readTree((String) r.get("content_json"))));
        }
        return out;
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 独立第二校验器（ADR-066 §10.3）：手写结构复核，覆盖 findingSchema 用到的关键字
     * （type / required / properties / additionalProperties=false / items / enum）。
     * 刻意不复用 networknt，作为对权威校验器的交叉佐证。
     */
    private static List<String> structuralViolations(JsonNode schema, JsonNode node) {
        List<String> out = new ArrayList<>();
        walk(schema, node, "", out);
        return out;
    }

    private static void walk(JsonNode schema, JsonNode node, String path, List<String> out) {
        if (schema == null || schema.isMissingNode()) {
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean hit = false;
            for (JsonNode e : enumNode) {
                if (e.equals(node)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                out.add(path + ": 不在枚举内");
            }
        }
        String type = schema.path("type").asText(null);
        if (type != null && !typeMatches(type, node)) {
            out.add(path + ": 类型应为 " + type);
            return;
        }
        if ("object".equals(type) && node.isObject()) {
            for (JsonNode req : schema.path("required")) {
                if (!node.has(req.asText())) {
                    out.add(path + "/" + req.asText() + ": 缺少必填字段");
                }
            }
            JsonNode props = schema.path("properties");
            boolean closed = schema.path("additionalProperties").isBoolean()
                    && !schema.path("additionalProperties").asBoolean();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode sub = props.get(e.getKey());
                if (sub == null) {
                    if (closed) {
                        out.add(path + "/" + e.getKey() + ": 不允许的额外字段");
                    }
                } else {
                    walk(sub, e.getValue(), path + "/" + e.getKey(), out);
                }
            }
        }
        if ("array".equals(type) && node.isArray()) {
            JsonNode items = schema.get("items");
            int i = 0;
            for (JsonNode el : node) {
                walk(items, el, path + "/" + i, out);
                i++;
            }
        }
    }

    private static boolean typeMatches(String type, JsonNode node) {
        return switch (type) {
            case "object" -> node.isObject();
            case "array" -> node.isArray();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            default -> true;
        };
    }

    // ------------------------------------------------------------------
    // Flow DSL 夹具（FlowDslValidationTest 同口径）
    // ------------------------------------------------------------------

    private static FlowDslDocument baseFlow() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("in", "input", Map.of()));
        nodes.add(node("out", "output", Map.of()));
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("in", "out", "out", "in"));
        return new FlowDslDocument(1, Map.of("name", "demo"),
                List.of(Map.of("name", "x", "type", "text", "required", true)),
                List.of(Map.of("name", "y", "type", "text")),
                Map.of(), Map.of("runTimeout", "30s"), nodes, edges, Map.of());
    }

    private static Map<String, Object> node(String id, String type, Map<String, Object> config) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("type", type);
        n.put("typeVersion", 1);
        n.put("label", id);
        n.put("config", new LinkedHashMap<>(config));
        return n;
    }

    private static Map<String, Object> edge(String from, String fromPort, String to, String toPort) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("fromNode", from);
        e.put("fromPort", fromPort);
        e.put("toNode", to);
        e.put("toPort", toPort);
        return e;
    }

    private static void wireThrough(FlowDslDocument doc, String mid) {
        doc.edges().clear();
        doc.edges().add(edge("in", "out", mid, "in"));
        doc.edges().add(edge(mid, "out", "out", "in"));
    }
}
