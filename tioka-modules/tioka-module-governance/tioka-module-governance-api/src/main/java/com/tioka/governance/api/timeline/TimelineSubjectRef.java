package com.tioka.governance.api.timeline;

/**
 * 时间线条目关联的主体（ADR-075 §2：{@code subject} 可多值）。例 {@code ("contract", "C-2026-0001")}。
 */
public record TimelineSubjectRef(String subjectType, String subjectId) {
}
