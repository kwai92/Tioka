package com.tioka.system.application.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P2-L6：W3C traceparent 手写解析单测（合法 / 非法 / 缺失三类；纯 JUnit5 离线可跑）。
 */
class TraceparentParserTest {

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";

    @Test
    @DisplayName("合法 traceparent 解析出 trace-id 与 parent-span-id")
    void parsesValidTraceparent() {
        TraceparentParser.Parsed p = TraceparentParser.parse(
                "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01");
        assertNotNull(p);
        assertEquals(VALID_TRACE_ID, p.traceId());
        assertEquals(VALID_SPAN_ID, p.spanId());
    }

    @Test
    @DisplayName("缺失 / null / 空白返回 null")
    void missingReturnsNull() {
        assertNull(TraceparentParser.parse(null));
        assertNull(TraceparentParser.parse(""));
        assertNull(TraceparentParser.parse("   "));
    }

    @Test
    @DisplayName("非法格式返回 null（长度、分隔、版本、大小写、全零）")
    void invalidReturnsNull() {
        assertNull(TraceparentParser.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID));
        assertNull(TraceparentParser.parse("00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01-extra"));
        assertNull(TraceparentParser.parse("01-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01"),
                "版本非 00 的非透明传播场景按非法处理");
        assertNull(TraceparentParser.parse("00-" + VALID_TRACE_ID.toUpperCase() + "-" + VALID_SPAN_ID + "-01"),
                "W3C 规定小写十六进制");
        assertNull(TraceparentParser.parse("00-" + "0".repeat(32) + "-" + VALID_SPAN_ID + "-01"),
                "全零 trace-id 非法");
        assertNull(TraceparentParser.parse("00-" + VALID_TRACE_ID + "-" + "0".repeat(16) + "-01"),
                "全零 parent-span-id 非法");
        assertNull(TraceparentParser.parse("00-" + "xyz".repeat(11) + "-" + VALID_SPAN_ID + "-01"));
        assertNull(TraceparentParser.parse("garbage"));
    }
}
