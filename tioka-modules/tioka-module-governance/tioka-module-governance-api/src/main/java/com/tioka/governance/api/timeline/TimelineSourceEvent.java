package com.tioka.governance.api.timeline;

import java.time.Instant;
import java.util.Map;

/**
 * 时间线来源事件（ADR-075 / OBS-08）。各域发布后由投影消费者写入 {@code timeline_entry}。
 *
 * @param sourceEventId   来源事件唯一标识（投影幂等键；同一事件重复投递只落一条）
 * @param eventType       来源事件名（如 {@code tioka.run.started.v1}）
 * @param requestId       关联键（ADR-073）
 * @param parentRequestId 派生请求的父 {@code request_id}（ADR-073）
 * @param subjectType     主体类型（如 {@code contract}、{@code knowledge_doc}、{@code agent}）
 * @param subjectId       主体标识
 * @param workspaceId     空间
 * @param occurredAt      发生时间
 * @param attrs           其余投影字段：{@code category}（必需，见 {@link TimelineCategory}）、
 *                        {@code title}、{@code summary}、{@code actor}、{@code outcome}、
 *                        {@code sourceType}、{@code sourceId}、{@code traceId}、{@code dataLabel}、{@code seq}
 */
public record TimelineSourceEvent(
        String sourceEventId,
        String eventType,
        String requestId,
        String parentRequestId,
        String subjectType,
        String subjectId,
        String workspaceId,
        Instant occurredAt,
        Map<String, Object> attrs
) {}
