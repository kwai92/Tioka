package com.tioka.flow.api.dsl;

import java.util.List;
import java.util.Map;

/**
 * Flow DSL v1 文档（ADR-043 §3.1）。布局与调试数据不在此对象。
 */
public record FlowDslDocument(
        int schemaVersion,
        Map<String, Object> meta,
        List<Map<String, Object>> inputs,
        List<Map<String, Object>> outputs,
        Map<String, Object> constants,
        Map<String, Object> settings,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges,
        Map<String, Object> subgraphs
) {
}
