package com.tioka.knowledge.infrastructure;

/** kb_chunk 带向量写入命令（48-CS）。docVersion：KNW-05 版本化分块；存量同步路径恒为 0。 */
public record ChunkInsertCommand(
        String id,
        String docId,
        String kbId,
        String workspaceId,
        String text,
        int ordinal,
        String vectorLiteral,
        int docVersion
) {
    /** 兼容旧调用：无版本（同步路径）。 */
    public ChunkInsertCommand(String id, String docId, String kbId, String workspaceId,
                              String text, int ordinal, String vectorLiteral) {
        this(id, docId, kbId, workspaceId, text, ordinal, vectorLiteral, 0);
    }
}
