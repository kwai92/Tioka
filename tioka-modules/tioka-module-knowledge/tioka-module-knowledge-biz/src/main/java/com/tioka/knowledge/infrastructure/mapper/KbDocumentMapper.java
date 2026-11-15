package com.tioka.knowledge.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentTimelineDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocumentDO> {

    /**
     * OBS-12 时间线重建（ADR-075 §4）：按时间段读回文档，并左连 {@code kb_base} 补齐空间。
     *
     * <p>重建/投影可能无 Principal，且左连的 {@code kb_base} 非 workspace 旁路表，
     * 故方法级旁路 ScopeLine——空间由 SQL 显式带出，隔离改在时间线查询侧做。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<KbDocumentTimelineDO> listByRange(@Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to);
}
