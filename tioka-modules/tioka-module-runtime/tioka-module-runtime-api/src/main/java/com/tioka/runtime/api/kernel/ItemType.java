package com.tioka.runtime.api.kernel;

/**
 * runtime_item 的类型，即前端唯一认识的渲染单元（ADR-016、ADR-028）。
 *
 * <p>新增类型必须先改本枚举与 {@code docs/contracts/events/runtime-event.schema.json}；
 * 前端遇到未知类型显示通用占位，不报错。
 */
public enum ItemType {
    MESSAGE("message"),
    REASONING("reasoning"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    FILE_REF("file_ref"),
    CITATION("citation"),
    CARD("card"),
    COMPACTION("compaction"),
    CHILD_THREAD("child_thread"),
    STATUS("status"),
    ERROR("error"),
    /** TRU-05 变更单（ADR-033）。 */
    CHANGE_SET("change_set");

    private final String wire;

    ItemType(String wire) { this.wire = wire; }

    public String wire() { return wire; }

    public static ItemType fromWire(String wire) {
        for (ItemType t : values()) {
            if (t.wire.equals(wire)) return t;
        }
        throw new IllegalArgumentException("unknown item type: " + wire);
    }
}
