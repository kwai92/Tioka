package com.tioka.knowledge.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunkDO> {
    int insertWithVector(@Param("id") String id, @Param("docId") String docId, @Param("kbId") String kbId,
                         @Param("workspaceId") String workspaceId,
                         @Param("text") String text, @Param("ordinal") int ordinal,
                         @Param("vectorLiteral") String vectorLiteral,
                         @Param("docVersion") int docVersion);

    int updateVector(@Param("id") String id, @Param("vectorLiteral") String vectorLiteral);

    /**
     * pgvector 近邻检索（禁止全表加载到应用侧）。
     * {@code <=>} 无法被 TenantLine 的 JSQLParser 解析，故忽略该拦截器；
     * 空间过滤靠 {@code kb_id}（调用方保证）。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> searchNearest(@Param("kbId") String kbId,
                                            @Param("vectorLiteral") String vectorLiteral,
                                            @Param("k") int k);

    /**
     * KNW-02：可见性下推到 SQL 后再 LIMIT（ADR-018），避免无权文档挤占 topK。
     * {@code <=>} 同样忽略 TenantLine；{@code workspace_id} 与 {@code vfSql} 由调用方写入。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<Map<String, Object>> searchVisible(Map<String, Object> params);
}
