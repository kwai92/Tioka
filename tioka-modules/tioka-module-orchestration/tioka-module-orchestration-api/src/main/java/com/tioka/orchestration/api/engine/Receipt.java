package com.tioka.orchestration.api.engine;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 通过内置工具 {@code orch.receipt} 交回的回执（ADR-020 修订第 4〜7 条，施工设计 M2-02）。
 * JSON 形状见 {@code docs/contracts/events/orch-receipt-v1.json}。回执整体校验后在一个事务里生效。
 */
public record Receipt(Outcome outcome, String summary, Map<String, Object> data, List<Action> actions) {

    public enum Outcome { DONE, NOTHING, FAILED, CANCELLED }

    public sealed interface Action permits Notice, Ask, Later, Emit {}

    /** @param to requester / owner / channel */
    public record Notice(String text, String to) implements Action {}

    /**
     * @param key       同一工单同 key 且审批未决时复用原审批
     * @param form      表单字段定义
     * @param onTimeout 超时默认答案，可空
     */
    public record Ask(String key, String title, List<Map<String, Object>> form, Map<String, List<String>> assignee,
                      Duration timeout, Map<String, Object> onTimeout) implements Action {}

    /** 同 key 视为改时间。 */
    public record Later(String key, OffsetDateTime at, String input) implements Action {}

    /** type 必须在规则版本的 emits 白名单内，否则进入 rejected。 */
    public record Emit(String type, String subjectKey, Map<String, String> attrs, Map<String, Object> body) implements Action {}
}
