package com.tioka.model.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ModelTurnFreezeMapper extends BaseMapper<ModelTurnFreezeDO> {

    /**
     * 轮次冻结写入：冲突时以先写者为准（MH-01）。
     *
     * @return 插入行数；0 表示已有冻结
     */
        int insertIfAbsent(@Param("threadId") String threadId,
                       @Param("turnId") String turnId,
                       @Param("bindingId") String bindingId,
                       @Param("reason") String reason);

        List<ModelTurnFreezeDO> listByThread(@Param("threadId") String threadId);

    /** MH-02：切换冻结 binding，failover_count + 1。 */
        int recordFailover(@Param("threadId") String threadId,
                       @Param("turnId") String turnId,
                       @Param("bindingId") String bindingId,
                       @Param("reason") String reason);
}
