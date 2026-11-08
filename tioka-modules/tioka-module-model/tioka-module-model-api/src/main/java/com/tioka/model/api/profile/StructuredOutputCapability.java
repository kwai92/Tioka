package com.tioka.model.api.profile;

import java.util.Map;
import java.util.Set;

/**
 * 模型结构化输出能力记录（ADR-066 §4），取代 SCHEMA/JSON/TOOL 三档枚举。
 */
public record StructuredOutputCapability(
        Set<StructuredOutputMode> modes,
        Set<String> keywords,
        Limits limits,
        Map<StructuredOutputMode, ThinkingCompat> thinkingCompat,
        boolean multimodalDowngrade,
        boolean promptKeyword,
        MaxTokensAdvice maxTokensAdvice,
        Stability stability,
        String refusalSignal,
        String truncationSignal,
        String verifiedAt,
        String conformance
) {
    public StructuredOutputCapability {
        if (modes == null) {
            modes = Set.of();
        } else {
            modes = Set.copyOf(modes);
        }
        if (keywords == null) {
            keywords = Set.of();
        } else {
            keywords = Set.copyOf(keywords);
        }
        if (thinkingCompat == null) {
            thinkingCompat = Map.of();
        } else {
            thinkingCompat = Map.copyOf(thinkingCompat);
        }
        if (maxTokensAdvice == null) {
            maxTokensAdvice = MaxTokensAdvice.UNSET;
        }
        if (stability == null) {
            stability = Stability.GA;
        }
    }

    /** 便捷工厂：ADR-037 三档能力。 */
    public static StructuredOutputCapability legacy(String schemaJsonOrTool) {
        StructuredOutputMode mode = StructuredOutputMode.fromLegacy(schemaJsonOrTool);
        if (mode == null) {
            throw new IllegalArgumentException("unknown structuredOutput: " + schemaJsonOrTool);
        }
        Set<String> keywords = switch (mode) {
            case STRICT_SCHEMA, STRICT_TOOL -> Set.of(
                    "type", "properties", "required", "additionalProperties",
                    "description", "enum", "items", "anyOf", "$defs", "$ref");
            default -> Set.of();
        };
        return new StructuredOutputCapability(
                Set.of(mode),
                keywords,
                null,
                Map.of(),
                false,
                mode == StructuredOutputMode.JSON_OBJECT,
                MaxTokensAdvice.UNSET,
                Stability.GA,
                null,
                null,
                null,
                null);
    }

    public record Limits(Integer maxDepth, Integer maxProperties, Integer maxEnumSize, Integer maxUnionTypes,
                         Integer maxStrictTools) {
    }

    public enum ThinkingCompat {
        YES,
        NO,
        UNVERIFIED
    }

    public enum MaxTokensAdvice {
        UNSET,
        ESTIMATE
    }

    public enum Stability {
        GA,
        BETA
    }
}
