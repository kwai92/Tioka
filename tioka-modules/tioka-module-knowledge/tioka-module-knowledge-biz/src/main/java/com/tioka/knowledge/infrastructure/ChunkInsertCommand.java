package com.tioka.knowledge.infrastructure;

import java.util.List;

/**
 * kb_chunk 带向量写入命令（48-CS）。docVersion：KNW-05 版本化分块；存量同步路径恒为 0。
 * KNW-03 / V179：父子块结构列——chunkType PARENT/CHILD；父块 vectorLiteral 为 NULL（无向量，
 * 检索向量召回天然排除）；blockIds 为来源 blockId 列表（坐标经 blockId 从解析结果取）。
 */
public record ChunkInsertCommand(
        String id,
        String docId,
        String kbId,
        String workspaceId,
        String text,
        int ordinal,
        String vectorLiteral,
        int docVersion,
        String chunkType,
        String parentChunkId,
        Integer pageStart,
        Integer pageEnd,
        List<String> blockIds,
        String contextPrefix
) {
    public static final String TYPE_PARENT = "PARENT";
    public static final String TYPE_CHILD = "CHILD";

    /** 兼容旧调用：无版本（同步路径存量语义，结构列缺省）。 */
    public ChunkInsertCommand(String id, String docId, String kbId, String workspaceId,
                              String text, int ordinal, String vectorLiteral) {
        this(id, docId, kbId, workspaceId, text, ordinal, vectorLiteral, 0);
    }

    /** 兼容旧调用：版本化（KNW-05 异步路径，结构列缺省）。 */
    public ChunkInsertCommand(String id, String docId, String kbId, String workspaceId,
                              String text, int ordinal, String vectorLiteral, int docVersion) {
        this(id, docId, kbId, workspaceId, text, ordinal, vectorLiteral, docVersion,
                TYPE_CHILD, null, null, null, null, null);
    }
}
