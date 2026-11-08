package com.tioka.infra.messaging.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tioka.infra.messaging.infrastructure.persistence.SysOutboxDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 平台级 outbox，无 workspace_id；旁路 ScopeLine。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SysOutboxMapper extends BaseMapper<SysOutboxDO> {

    List<SysOutboxDO> selectUnsent(@Param("limit") int limit);

    int markSent(@Param("id") long id, @Param("sentAt") Instant sentAt);

    int markAttemptFailed(@Param("id") long id, @Param("lastError") String lastError);

    int deleteSentBefore(@Param("before") Instant before);

    int notifyOutbox();
}
