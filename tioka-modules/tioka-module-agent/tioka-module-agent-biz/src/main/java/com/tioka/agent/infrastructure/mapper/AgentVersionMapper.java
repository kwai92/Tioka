package com.tioka.agent.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.agent.infrastructure.persistence.AgentVersionDO;
import com.tioka.agent.infrastructure.persistence.AgentVersionTimelineDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentVersionMapper extends BaseMapper<AgentVersionDO> {

    /**
     * OBS-12 时间线重建：按发布/创建时间段读回版本（升序），左连 {@code agent_agent} 补空间。
     * 异步重建无 Principal，方法级旁路 ScopeLine（{@code agent_version} 无 workspace_id 列，
     * 但左连的 {@code agent_agent} 非 exempt，须显式忽略）。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<AgentVersionTimelineDO> listByRange(@Param("from") LocalDateTime from,
                                             @Param("to") LocalDateTime to);
}
