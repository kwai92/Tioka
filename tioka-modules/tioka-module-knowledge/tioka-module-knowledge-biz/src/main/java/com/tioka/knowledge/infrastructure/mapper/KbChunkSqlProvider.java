package com.tioka.knowledge.infrastructure.mapper;

import java.util.List;
import java.util.Map;

/**
 * KNW-02：ACL 下推的近邻 SQL（pgvector {@code <=>} 需动态拼接可见性片段）。
 */
public final class KbChunkSqlProvider {

    public String searchVisible(Map<String, Object> params) {
        @SuppressWarnings("unchecked")
        List<String> kbIds = (List<String>) params.get("kbIds");
        boolean vfAll = Boolean.TRUE.equals(params.get("vfAll"));
        String vfSql = params.get("vfSql") == null ? "TRUE" : String.valueOf(params.get("vfSql"));

        StringBuilder sql = new StringBuilder("""
                SELECT c.id AS chunk_id, c.doc_id AS doc_id, c.kb_id AS kb_id, c.text AS text,
                       c.ordinal AS ordinal, COALESCE(d.title, '') AS title,
                       (c.embedding_vec <=> CAST(#{vectorLiteral} AS vector)) AS distance
                FROM kb_chunk c
                JOIN kb_base b ON b.id = c.kb_id AND b.deleted = FALSE
                LEFT JOIN kb_document d ON d.id = c.doc_id AND d.deleted = FALSE
                WHERE c.deleted = FALSE
                  AND c.embedding_vec IS NOT NULL
                  AND c.workspace_id = #{workspaceId}
                """);
        if (kbIds != null && !kbIds.isEmpty()) {
            sql.append(" AND c.kb_id IN (");
            for (int i = 0; i < kbIds.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("#{kbIds[").append(i).append("]}");
            }
            sql.append(')');
        }
        if (!vfAll) {
            sql.append(" AND (").append(vfSql).append(')');
        }
        sql.append("""
                 ORDER BY c.embedding_vec <=> CAST(#{vectorLiteral} AS vector)
                 LIMIT #{k}
                """);
        return sql.toString();
    }
}
