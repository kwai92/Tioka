package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.tioka.ontology.api.schema.ProcessedOutput;
import com.tioka.ontology.api.schema.RawModelOutput;
import com.tioka.ontology.api.schema.ReasonCode;
import com.tioka.ontology.api.schema.SchemaError;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出处理流水线第 1〜4 步（ADR-066 §5 / SCH-07）：完成状态 → 提取 → 严格解析 → 白名单规整。
 *
 * <p>纯逻辑、无 Spring 依赖。第 5〜8 步（Schema 校验、语义规则、证据与引用、领域钩子）由
 * {@link SchemaRegistry} 与 Runtime 的 {@code OutputStage} 承接。本类的契约是
 * <b>任何输入都返回 {@link ProcessedOutput}，绝不抛异常</b>（ADR-066 §1.3），内部异常转
 * {@link ReasonCode#INTERNAL}。
 *
 * <ul>
 *   <li>第 3 步严格解析：拒绝注释、尾逗号、单引号、{@code NaN}；重复键拒绝；嵌套深度 ≤ 32；
 *       单个字符串 ≤ 64 KB；数字按十进制精确值保存（金额不丢精度）。</li>
 *   <li>第 4 步白名单规整：枚举大小写不敏感唯一匹配、字符串首尾空白、{@code x-tioka-coerce}
 *       的 {@code number} / {@code date}；不补字段、不截断、不猜测枚举。</li>
 * </ul>
 */
public class OutputPipeline {

    /** 严格档 S4 / 第 3 步：嵌套深度上限（ADR-066 §3 / §5）。 */
    static final int MAX_DEPTH = 32;
    /** 第 3 步：单个字符串上限 64 KB。 */
    static final int MAX_STRING_LENGTH = 64 * 1024;

    private static final Pattern COERCE_NUMBER = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern CN_DATE =
            Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    private static final Pattern SLASH_DATE = Pattern.compile("(\\d{4})/(\\d{1,2})/(\\d{1,2})");

    private static final ObjectMapper STRICT_MAPPER = strictMapper();

    private final ObjectMapper mapper = STRICT_MAPPER;

    /** 严格档解析器：拒绝重复键、超深嵌套、超长字符串、尾随内容；数字按十进制精确值保存。 */
    private static ObjectMapper strictMapper() {
        JsonFactory strict = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(MAX_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        ObjectMapper m = JsonMapper.builder(strict)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build();
        m.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        return m;
    }

    // ==================== 编排 ====================

    /** 第 1〜4 步；任何异常转 {@link ReasonCode#INTERNAL}（ADR-066 §1.3）。 */
    public ProcessedOutput process(JsonNode schema, RawModelOutput raw) {
        try {
            ReasonCode step1 = classify(raw);
            if (step1 != null) {
                return ProcessedOutput.failed(step1, new SchemaError("", step1.name(), describe(step1)),
                        lenientCandidate(raw == null ? null : raw.text()));
            }
            Extracted extracted = locate(clean(raw.text()));
            if (extracted.reason() != null) {
                return ProcessedOutput.failed(extracted.reason(),
                        new SchemaError("", extracted.reason().name(), describe(extracted.reason())), null);
            }
            Parsed parsed = parse(extracted.json());
            if (parsed.reason() != null) {
                return ProcessedOutput.failed(parsed.reason(),
                        new SchemaError("", parsed.reason().name(), describe(parsed.reason())), null);
            }
            Normalized normalized = normalize(schema, parsed.node());
            return ProcessedOutput.success(normalized.payload(), normalized.notes());
        } catch (RuntimeException e) {
            return ProcessedOutput.failed(ReasonCode.INTERNAL,
                    new SchemaError("", ReasonCode.INTERNAL.name(), "输出流水线内部异常：" + e), null);
        }
    }

    // ==================== 第 1 步：完成状态 ====================

    /** 返回非 {@code null} 即完成状态失败，不进入后续步骤。 */
    public ReasonCode classify(RawModelOutput raw) {
        if (raw == null) {
            return ReasonCode.EMPTY;
        }
        if (raw.truncated()) {
            return ReasonCode.TRUNCATED;
        }
        if (raw.contentFiltered()) {
            return ReasonCode.CONTENT_FILTERED;
        }
        if (raw.refused()) {
            return ReasonCode.REFUSED;
        }
        if (raw.text() == null || raw.text().isBlank()) {
            return ReasonCode.EMPTY;
        }
        return null;
    }

    private static String describe(ReasonCode reason) {
        return switch (reason) {
            case TRUNCATED -> "达到输出长度上限";
            case REFUSED -> "模型拒答";
            case CONTENT_FILTERED -> "供应商内容过滤";
            case EMPTY -> "内容为空或只有空白";
            case NO_JSON -> "找不到顶层 JSON 值";
            case MULTIPLE_JSON -> "有多个顶层 JSON 值";
            case SYNTAX -> "JSON 语法错误、重复键或超深嵌套";
            default -> reason.name();
        };
    }

    // ==================== 第 2 步：提取 ====================

    /** 去掉首尾空白、BOM 与单个 Markdown 代码围栏（ADR-066 §5 第 2 步）。 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw;
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }
        s = s.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) {
                String inner = s.substring(nl + 1);
                int close = inner.lastIndexOf("```");
                if (close >= 0 && inner.substring(close + 3).isBlank()) {
                    s = inner.substring(0, close).strip();
                } else {
                    s = inner.strip();
                }
            }
        }
        return s;
    }

    /**
     * 定位唯一的顶层 JSON 值（ADR-066 §5 第 2 步）。
     *
     * <p>顶层 JSON 值 = 括号配平的 {@code {...}} / {@code [...]} 结构体，或整段文本本身就是一个
     * JSON 标量（数字、字符串、{@code true}/{@code false}/{@code null}）。结构体以外的说明性文字
     * 不计为 JSON 值，因此「结果如下：{@code {"a":1}}」按唯一顶层值提取 {@code {"a":1}}。
     *
     * <p>0 个值 → {@link ReasonCode#NO_JSON}；多于 1 个值 → {@link ReasonCode#MULTIPLE_JSON}。
     */
    static Extracted locate(String cleaned) {
        if (cleaned == null) {
            return new Extracted(null, ReasonCode.NO_JSON);
        }
        List<int[]> spans = new ArrayList<>();
        int n = cleaned.length();
        int i = 0;
        while (i < n) {
            char c = cleaned.charAt(i);
            if (c == '{' || c == '[') {
                int end = matchBalanced(cleaned, i);
                spans.add(new int[]{i, end});
                i = end;
            } else {
                i++;
            }
        }
        if (spans.size() > 1) {
            return new Extracted(null, ReasonCode.MULTIPLE_JSON);
        }
        if (spans.size() == 1) {
            return new Extracted(cleaned.substring(spans.get(0)[0], spans.get(0)[1]), null);
        }
        // 无结构体：整段（去空白）本身是 JSON 标量时按标量提取
        String scalar = cleaned.strip();
        if (!scalar.isEmpty() && isJsonScalar(scalar)) {
            return new Extracted(scalar, null);
        }
        return new Extracted(null, ReasonCode.NO_JSON);
    }

    /** 整段文本是否是单个 JSON 标量（数字 / 字符串 / {@code true} / {@code false} / {@code null}）。 */
    private static boolean isJsonScalar(String text) {
        try {
            JsonNode node = STRICT_MAPPER.readTree(text);
            return node != null && node.isValueNode();
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回结构体结束后的下标；未闭合时返回串尾（交由第 3 步报 {@code SYNTAX}）。 */
    private static int matchBalanced(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return s.length();
    }

    // ==================== 第 3 步：严格解析 ====================

    /** 严格 JSON 解析；失败返回 {@link ReasonCode#SYNTAX}。 */
    public Parsed parse(String json) {
        if (json == null || json.isBlank()) {
            return new Parsed(null, ReasonCode.NO_JSON);
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || node.isNull() || node.isMissingNode()) {
                return new Parsed(null, ReasonCode.NO_JSON);
            }
            return new Parsed(node, null);
        } catch (StreamConstraintsException e) {
            return new Parsed(null, ReasonCode.SYNTAX);
        } catch (Exception e) {
            return new Parsed(null, ReasonCode.SYNTAX);
        }
    }

    private JsonNode lenientCandidate(String raw) {
        try {
            Extracted extracted = locate(clean(raw));
            if (extracted.json() == null) {
                return null;
            }
            JsonNode node = mapper.readTree(extracted.json());
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 第 4 步：白名单规整 ====================

    /** 按权威 Schema 做确定性规整（ADR-066 §5 第 4 步）。 */
    public Normalized normalize(JsonNode schema, JsonNode payload) {
        List<String> notes = new ArrayList<>();
        JsonNode out = walk(schema, payload, "", notes);
        return new Normalized(out, notes);
    }

    private JsonNode walk(JsonNode schema, JsonNode payload, String pointer, List<String> notes) {
        if (schema == null || payload == null) {
            return payload;
        }
        if (schema.isObject() && payload.isObject()) {
            ObjectNode obj = ((ObjectNode) payload).deepCopy();
            JsonNode props = schema.get("properties");
            if (props != null && props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    if (obj.has(e.getKey())) {
                        obj.set(e.getKey(),
                                walk(e.getValue(), obj.get(e.getKey()), pointer + "/" + e.getKey(), notes));
                    }
                }
            }
            return obj;
        }
        if (payload.isArray()) {
            JsonNode items = schema.get("items");
            if (items != null && items.isObject()) {
                ArrayNode arr = ((ArrayNode) payload).deepCopy();
                for (int i = 0; i < arr.size(); i++) {
                    arr.set(i, walk(items, arr.get(i), pointer + "/" + i, notes));
                }
                return arr;
            }
            return payload;
        }
        return leaf(schema, payload, pointer, notes);
    }

    private JsonNode leaf(JsonNode schema, JsonNode payload, String pointer, List<String> notes) {
        JsonNode node = payload;
        if (node.isTextual()) {
            String original = node.asText();
            String trimmed = original.strip();
            if (!trimmed.equals(original)) {
                node = TextNode.valueOf(trimmed);
                notes.add(pointer + ": trim");
            }
        }
        JsonNode enums = schema.get("enum");
        if (enums != null && enums.isArray() && node.isTextual()) {
            String canonical = enumCanonical(enums, node.asText());
            if (canonical != null && !canonical.equals(node.asText())) {
                node = TextNode.valueOf(canonical);
                notes.add(pointer + ": enum->" + canonical);
            }
        }
        String coerce = schema.path("x-tioka-coerce").asText(null);
        if (coerce != null && node.isTextual()) {
            if ("number".equalsIgnoreCase(coerce)) {
                JsonNode num = coerceNumber(node.asText());
                if (num != null) {
                    notes.add(pointer + ": coerce number");
                    return num;
                }
            } else if ("date".equalsIgnoreCase(coerce)) {
                String iso = coerceDate(node.asText());
                if (iso != null) {
                    notes.add(pointer + ": coerce date");
                    return TextNode.valueOf(iso);
                }
            }
        }
        return node;
    }

    /** 大小写不敏感且唯一匹配时返回规范值；0 个或多个匹配返回 {@code null}（不猜测）。 */
    static String enumCanonical(JsonNode enums, String value) {
        String match = null;
        int count = 0;
        for (JsonNode e : enums) {
            if (e.isTextual() && e.asText().equalsIgnoreCase(value)) {
                match = e.asText();
                count++;
            }
        }
        return count == 1 ? match : null;
    }

    /** 只含数字、小数点、千分位逗号、正负号的字符串转数字；否则 {@code null}。 */
    static JsonNode coerceNumber(String raw) {
        String t = raw.strip().replace(",", "");
        if (t.isEmpty() || !COERCE_NUMBER.matcher(t).matches()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(t);
            if (d.scale() <= 0) {
                try {
                    return LongNode.valueOf(d.longValueExact());
                } catch (ArithmeticException overflow) {
                    return DecimalNode.valueOf(d);
                }
            }
            return DecimalNode.valueOf(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code YYYY年M月D日} / {@code YYYY/M/D} → ISO 日期；否则 {@code null}。 */
    static String coerceDate(String raw) {
        String t = raw.strip();
        Matcher m = CN_DATE.matcher(t);
        if (!m.matches()) {
            m = SLASH_DATE.matcher(t);
            if (!m.matches()) {
                return null;
            }
        }
        int year = Integer.parseInt(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        return String.format("%04d-%02d-%02d", year, month, day);
    }

    // ==================== 结果载体 ====================

    /** 第 2 步结果：{@code reason != null} 时失败。 */
    public record Extracted(String json, ReasonCode reason) {}

    /** 第 3 步结果：{@code reason != null} 时失败。 */
    public record Parsed(JsonNode node, ReasonCode reason) {}

    /** 第 4 步结果：规整后的 payload 与规整记录。 */
    public record Normalized(JsonNode payload, List<String> notes) {}

    public ObjectMapper mapper() {
        return mapper;
    }
}
