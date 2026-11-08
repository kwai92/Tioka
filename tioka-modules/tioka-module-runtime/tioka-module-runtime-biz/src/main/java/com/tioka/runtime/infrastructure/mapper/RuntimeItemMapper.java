package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RuntimeItemMapper {

        Integer assertLease(@Param("turnId") String turnId, @Param("leaseVersion") long leaseVersion);

        Long nextItemSeq(String threadId);

        void insert(RuntimeItemDO item);

        java.util.List<RuntimeItemDO> listByThread(String threadId);

        java.util.List<RuntimeItemDO> listAfterSeq(@Param("threadId") String threadId,
                                               @Param("afterSeq") long afterSeq,
                                               @Param("limit") int limit);

        java.util.List<RuntimeItemDO> listByTurn(String turnId);
}
