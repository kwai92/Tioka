package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Mapper
public interface ModelUsageMapper extends BaseMapper<ModelUsageDO> {

        List<Map<String, Object>> summaryByWorkspace(@Param("workspaceId") String workspaceId,
                                                 @Param("from") Instant from,
                                                 @Param("to") Instant to);

        List<Map<String, Object>> summaryByAgent(@Param("workspaceId") String workspaceId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

        List<Map<String, Object>> summaryByUser(@Param("workspaceId") String workspaceId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to);

        List<Map<String, Object>> summaryByModel(@Param("workspaceId") String workspaceId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    /** P2-L5（GAP-L5-2）：成本按供给侧 provider 分摊。 */
    List<Map<String, Object>> summaryByProvider(@Param("workspaceId") String workspaceId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

        List<Map<String, Object>> trendByDay(@Param("scopeType") String scopeType,
                                         @Param("scopeId") String scopeId,
                                         @Param("workspaceId") String workspaceId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);

        Map<String, Object> sessionAggregate(@Param("threadId") String threadId);

    /** GOV-03 对账：workspace / user / agent 本窗口 token 合计。 */
        Long sumTokensByScope(@Param("scopeType") String scopeType,
                          @Param("scopeId") String scopeId,
                          @Param("from") Instant from,
                          @Param("to") Instant to);

    /** P2-L5（GAP-L5-1）：binding 自月初以来的 token 消耗合计（供给侧预算窗口）。 */
    Long sumTokensByBinding(@Param("bindingId") String bindingId,
                            @Param("from") Instant from);

    /** P2-L5（GAP-L5-1）：binding 自月初以来的成本合计（微美分，供给侧预算窗口）。 */
    Long sumCostByBinding(@Param("bindingId") String bindingId,
                          @Param("from") Instant from);
}
