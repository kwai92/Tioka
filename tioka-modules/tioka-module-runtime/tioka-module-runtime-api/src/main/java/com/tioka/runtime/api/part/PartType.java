package com.tioka.runtime.api.part;

/**
 * Part Schema v0 类型枚举（与 JSON Schema 同步，W0-07 冻结）。
 */
public enum PartType {
    TEXT("text"),
    MARKDOWN("markdown"),
    THINKING("thinking"),
    REASONING_SUMMARY("reasoning_summary"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    CITATION("citation"),
    ARTIFACT("artifact"),
    CODE_INTERPRETER("code_interpreter"),
    INTERACTIVE_CARD("interactive_card"),
    APPROVAL_CARD("approval_card"),
    FORM_WIZARD("form_wizard"),
    STATUS("status"),
    ERROR("error"),
    IMAGE("image"),
    AUDIO("audio"),
    FILE_REF("file_ref"),
    AGENT_HANDOFF("agent_handoff");

    private final String wire;

    PartType(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    public static PartType fromWire(String wire) {
        for (PartType t : values()) {
            if (t.wire.equals(wire)) return t;
        }
        throw new IllegalArgumentException("unknown part type: " + wire);
    }
}
