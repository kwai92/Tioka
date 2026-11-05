package com.tioka.runtime.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class RuntimeMqTopics {

    public static final String TOPIC = "tioka-runtime";

    public static final String TAG_SESSION_CREATED = "session.created";
    public static final String TAG_SESSION_COMPLETED = "session.completed";
    public static final String TAG_TURN_STARTED = "turn.started";
    public static final String TAG_TURN_COMPLETED = "turn.completed";
    public static final String TAG_PART_APPENDED = "part.appended";
    public static final String TAG_TOOL_INVOKED = "tool.invoked";
    public static final String TAG_APPROVAL_REQUESTED = "approval.requested";
    public static final String TAG_SUBAGENT_FINISHED = "subagent.finished";
    public static final String TAG_USAGE_REPORTED = "usage.reported";
    public static final String TAG_SANDBOX_DESTROYED = "sandbox.destroyed";

    private RuntimeMqTopics() {
    }
}
