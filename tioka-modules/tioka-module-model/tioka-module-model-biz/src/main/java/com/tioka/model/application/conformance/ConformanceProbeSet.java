package com.tioka.model.application.conformance;

import java.util.ArrayList;
import java.util.List;

/**
 * SCH-10（ADR-066 §10.2）：平台一致性测试集。
 *
 * <p>覆盖「严格档全部关键字组合、深度与规模上限、枚举、可空、引用、中文长文本、多模态输入、
 * 思考模式开关」十类维度，对每个模型版本逐生成方式运行，测出 {@code modes}、{@code keywords}、
 * {@code limits}、{@code thinkingCompat}、{@code multimodalDowngrade} 的实际值。
 *
 * <p>结构类探针（{@code DEPTH}/{@code SIZE}/{@code ENUM}/{@code NULLABLE}）按 ADR-066 §3 S4
 * 的平台上限设置梯度：上限以内的探针决定该方式是否可用（{@link #withinPlatformCeiling}），
 * 超过上限的探针只用于测出模型真实上限（{@code limits}）。
 */
public final class ConformanceProbeSet {

    /** 平台严格档上限（ADR-066 §3 S4）：嵌套深度 ≤ 5、属性总数 ≤ 100、枚举 ≤ 200、联合类型 ≤ 16。 */
    static final int CEILING_DEPTH = 5;
    static final int CEILING_PROPERTIES = 100;
    static final int CEILING_ENUM = 200;
    static final int CEILING_UNIONS = 16;

    /** 严格档关键字（ADR-066 §3 S1/S5/S8 与 §4 {@code keywords} 例）。 */
    private static final List<String> STRICT_KEYWORDS = List.of(
            "type", "properties", "required", "additionalProperties", "description", "enum", "items",
            "anyOf", "$defs", "$ref", "minLength", "maxLength", "minItems", "maxItems");

    private static final int[] DEPTH_LADDER = {1, 3, CEILING_DEPTH, 8};
    private static final int[] SIZE_LADDER = {10, 50, CEILING_PROPERTIES, 150};
    private static final int[] ENUM_LADDER = {10, 100, CEILING_ENUM, 300};
    private static final int[] UNION_LADDER = {4, 8, CEILING_UNIONS, 24};

    private static final String CJK_LONG_TEXT = """
            公司名称：上海示例实业有限公司。统一社会信用代码：91310000MA1FL0XXXX。
            注册地址：上海市浦东新区示例路 100 号 8 号楼 3 层。法定代表人：张示例。
            本合同项下的租赁物为位于上述地址的办公场所，建筑面积 1,286.42 平方米，\
            租赁期限自 2026 年 1 月 1 日起至 2028 年 12 月 31 日止，共 36 个月。\
            首年租金单价为每日每平方米人民币 5.20 元，此后每年按 3% 递增；\
            押金为三个月租金，于签署后五个工作日内支付。承租方享有同等条件下的优先续租权，\
            但须于租期届满前九十日以书面形式通知出租方。任何一方变更本合同条款，\
            均须经双方书面签署补充协议后方可生效。""";

    private static final List<ConformanceProbe> PROBES = build();

    private ConformanceProbeSet() {
    }

    /** 全部探针（顺序稳定）。 */
    public static List<ConformanceProbe> probes() {
        return PROBES;
    }

    /**
     * 该探针是否落在平台严格档上限以内。
     *
     * <p>方式可用性只需上限以内的结构探针全部通过；超过上限的探针只用于测出模型真实上限
     * （ADR-066 §3 S4：平台发布的严格档 Schema 本身不可能超过上限）。
     */
    public static boolean withinPlatformCeiling(ConformanceProbe probe) {
        return switch (probe.category()) {
            case DEPTH -> probe.level() <= CEILING_DEPTH;
            case SIZE -> probe.level() <= CEILING_PROPERTIES;
            case ENUM -> probe.level() <= CEILING_ENUM;
            case NULLABLE -> probe.level() <= CEILING_UNIONS;
            default -> true;
        };
    }

    private static List<ConformanceProbe> build() {
        List<ConformanceProbe> probes = new ArrayList<>();

        probes.add(new ConformanceProbe("baseline", ConformanceProbe.Category.STRICT_BASELINE, 0, null,
                false, false, "请按给定 Schema 输出一个最小对象。", baselineSchema()));

        for (String keyword : STRICT_KEYWORDS) {
            probes.add(new ConformanceProbe("keyword-" + keyword, ConformanceProbe.Category.KEYWORD, 0,
                    keyword, false, false, "请输出使用了「" + keyword + "」关键字的严格 JSON 对象。",
                    keywordSchema(keyword)));
        }

        for (int level : DEPTH_LADDER) {
            probes.add(new ConformanceProbe("depth-" + level, ConformanceProbe.Category.DEPTH, level, null,
                    false, false, "请输出嵌套深度为 " + level + " 层的对象，每层都有子对象。",
                    nestedSchema(level)));
        }
        for (int level : SIZE_LADDER) {
            probes.add(new ConformanceProbe("size-" + level, ConformanceProbe.Category.SIZE, level, null,
                    false, false, "请输出包含 " + level + " 个字段的对象，每个字段都要有值。",
                    wideSchema(level)));
        }
        for (int level : ENUM_LADDER) {
            probes.add(new ConformanceProbe("enum-" + level, ConformanceProbe.Category.ENUM, level, null,
                    false, false, "请从 " + level + " 个候选中选择一个，输出其名称。", enumSchema(level)));
        }
        for (int level : UNION_LADDER) {
            probes.add(new ConformanceProbe("union-" + level, ConformanceProbe.Category.NULLABLE, level, null,
                    false, false, "请输出对象，其 " + level + " 个字段均可为空。", unionSchema(level)));
        }

        probes.add(new ConformanceProbe("ref", ConformanceProbe.Category.REF, 0, null, false, false,
                "请按给定 Schema 输出对象，其中 leaf 字段复用 $defs 中的定义。", refSchema()));

        probes.add(new ConformanceProbe("cjk-long", ConformanceProbe.Category.CJK_LONG, 0, null, false, false,
                "阅读下面的中文长文本，按 Schema 输出中文摘要（不超过 80 字）。\n" + CJK_LONG_TEXT,
                singleStringSchema("summary", "中文摘要")));

        probes.add(new ConformanceProbe("multimodal", ConformanceProbe.Category.MULTIMODAL, 0, null, true, false,
                "阅读随附图片，按 Schema 输出图中标题。", singleStringSchema("title", "图中标题")));

        probes.add(new ConformanceProbe("thinking", ConformanceProbe.Category.THINKING, 0, null, false, true,
                "请开启思考模式，推理后按 Schema 输出结论。", singleStringSchema("conclusion", "结论")));

        return List.copyOf(probes);
    }

    // ==================== Schema 构造 ====================

    private static String baselineSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"name\":{\"type\":\"string\",\"description\":\"名称\"}},"
                + "\"required\":[\"name\"]}";
    }

    private static String singleStringSchema(String field, String description) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"" + field
                + "\":{\"type\":\"string\",\"description\":\"" + description + "\"}},\"required\":[\"" + field + "\"]}";
    }

    private static String keywordSchema(String keyword) {
        return switch (keyword) {
            case "type" -> "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"properties\":{\"v\":{\"type\":\"string\",\"description\":\"值\"}},\"required\":[\"v\"]}";
            case "properties", "required", "additionalProperties" -> baselineSchema();
            case "description" -> "{\"type\":\"object\",\"additionalProperties\":false,\"description\":\"对象说明\","
                    + "\"properties\":{\"v\":{\"type\":\"string\",\"description\":\"值\"}},\"required\":[\"v\"]}";
            case "enum" -> "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"properties\":{\"kind\":{\"type\":\"string\",\"description\":\"类型\","
                    + "\"enum\":[\"alpha\",\"beta\"]}},\"required\":[\"kind\"]}";
            case "items" -> "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"properties\":{\"tags\":{\"type\":\"array\",\"description\":\"标签\","
                    + "\"items\":{\"type\":\"string\",\"description\":\"标签项\"}}},\"required\":[\"tags\"]}";
            case "anyOf" -> "{\"type\":\"object\",\"additionalProperties\":false,"
                    + "\"properties\":{\"v\":{\"description\":\"可空值\","
                    + "\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}},\"required\":[\"v\"]}";
            case "$defs" -> defsSchema(false);
            case "$ref" -> defsSchema(true);
            case "minLength" -> stringConstraintSchema("\"minLength\":3");
            case "maxLength" -> stringConstraintSchema("\"maxLength\":8");
            case "minItems" -> arrayConstraintSchema("\"minItems\":1");
            case "maxItems" -> arrayConstraintSchema("\"maxItems\":4");
            default -> baselineSchema();
        };
    }

    private static String defsSchema(boolean useRef) {
        String defs = "\"$defs\":{\"Leaf\":{\"type\":\"object\",\"additionalProperties\":false,"
                + "\"properties\":{\"v\":{\"type\":\"string\",\"description\":\"值\"}},\"required\":[\"v\"]}}";
        String field = useRef
                ? "\"leaf\":{\"description\":\"叶子\",\"$ref\":\"#/$defs/Leaf\"}"
                : "\"v\":{\"type\":\"string\",\"description\":\"值\"}";
        String required = useRef ? "\"leaf\"" : "\"v\"";
        return "{\"type\":\"object\",\"additionalProperties\":false," + defs + ","
                + "\"properties\":{" + field + "},\"required\":[" + required + "]}";
    }

    private static String stringConstraintSchema(String constraint) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"s\":{\"type\":\"string\","
                + "\"description\":\"短文本\"," + constraint + "}},\"required\":[\"s\"]}";
    }

    private static String arrayConstraintSchema(String constraint) {
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"items\":{\"type\":\"array\","
                + "\"description\":\"列表\",\"items\":{\"type\":\"string\",\"description\":\"项\"},"
                + constraint + "}},\"required\":[\"items\"]}";
    }

    private static String nestedSchema(int depth) {
        String node = "{\"type\":\"string\",\"description\":\"叶子\"}";
        for (int i = 0; i < Math.max(depth, 1); i++) {
            node = "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"level" + i + "\":"
                    + node + "},\"required\":[\"level" + i + "\"]}";
        }
        return node;
    }

    private static String wideSchema(int count) {
        StringBuilder properties = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                properties.append(',');
                required.append(',');
            }
            properties.append("\"f").append(i).append("\":{\"type\":\"string\",\"description\":\"字段")
                    .append(i).append("\"}");
            required.append("\"f").append(i).append('"');
        }
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                + "},\"required\":[" + required + "]}";
    }

    private static String enumSchema(int size) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append("\"v").append(i).append('"');
        }
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"kind\":{\"type\":\"string\","
                + "\"description\":\"类型\",\"enum\":[" + values + "]}},\"required\":[\"kind\"]}";
    }

    private static String unionSchema(int size) {
        StringBuilder properties = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                properties.append(',');
                required.append(',');
            }
            properties.append("\"u").append(i).append("\":{\"description\":\"可为空的字段\","
                    + "\"anyOf\":[{\"type\":\"string\"},{\"type\":\"null\"}]}");
            required.append("\"u").append(i).append('"');
        }
        return "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{" + properties
                + "},\"required\":[" + required + "]}";
    }

    private static String refSchema() {
        return defsSchema(true);
    }
}
