package com.tioka.knowledge.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunkDO> {
    @Insert("""
INSERT INTO kb_chunk(id, doc_id, kb_id, text, ordinal, embedding_vec, create_at, update_at, create_by, update_by, deleted)
VALUES (#{id}, #{docId}, #{kbId}, #{text}, #{ordinal}, CAST(#{vectorLiteral} AS vector),
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system', FALSE)
""")
    int insertWithVector(@Param("id") String id, @Param("docId") String docId, @Param("kbId") String kbId,
                         @Param("text") String text, @Param("ordinal") int ordinal,
                         @Param("vectorLiteral") String vectorLiteral);

    @Update("""
UPDATE kb_chunk SET embedding_vec = CAST(#{vectorLiteral} AS vector), update_at = CURRENT_TIMESTAMP
WHERE id = #{id} AND deleted = FALSE
""")
    int updateVector(@Param("id") String id, @Param("vectorLiteral") String vectorLiteral);

    /**
     * pgvector 近邻检索（禁止全表加载到应用侧）。
     * {@code <=>} 无法被 TenantLine 的 JSQLParser 解析，故忽略该拦截器；
     * kb_chunk 本就不在 workspace 作用域表清单，过滤靠 kb_id。
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
SELECT c.id AS chunk_id, c.doc_id AS doc_id, c.text AS text, c.ordinal AS ordinal,
       COALESCE(d.title, '') AS title,
       (c.embedding_vec <=> CAST(#{vectorLiteral} AS vector)) AS distance
FROM kb_chunk c
LEFT JOIN kb_document d ON d.id = c.doc_id AND d.deleted = FALSE
WHERE c.kb_id = #{kbId} AND c.deleted = FALSE
  AND c.embedding_vec IS NOT NULL
ORDER BY c.embedding_vec <=> CAST(#{vectorLiteral} AS vector)
LIMIT #{k}
""")
    List<Map<String, Object>> searchNearest(@Param("kbId") String kbId,
                                            @Param("vectorLiteral") String vectorLiteral,
                                            @Param("k") int k);
}
