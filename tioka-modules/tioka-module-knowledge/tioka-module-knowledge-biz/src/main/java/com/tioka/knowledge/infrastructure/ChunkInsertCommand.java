package com.tioka.knowledge.infrastructure;

/** kb_chunk 带向量写入命令（48-CS）。 */
public record ChunkInsertCommand(
        String id,
        String docId,
        String kbId,
        String workspaceId,
        String text,
        int ordinal,
        String vectorLiteral
) {}
