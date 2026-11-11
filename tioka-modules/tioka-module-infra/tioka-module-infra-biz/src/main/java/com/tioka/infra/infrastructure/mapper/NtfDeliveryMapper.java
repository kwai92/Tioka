package com.tioka.infra.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** ntf_delivery 平台通知投递，无 workspace_id；旁路 ScopeLine。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface NtfDeliveryMapper {

    /** 按 dedup_key 幂等插入；冲突不更新。返回 1 = 新插入，0 = 已存在（去重命中）。 */
    int insertIfAbsent(NtfDeliveryDO row);

    NtfDeliveryDO findById(@Param("deliveryId") String deliveryId);

    /** 仅 PENDING → SENT；返回影响行数（终态幂等保护）。 */
    int markSent(@Param("deliveryId") String deliveryId,
                 @Param("externalRef") String externalRef,
                 @Param("sentAt") java.time.Instant sentAt);

    /** 仅 PENDING → FAILED（确定性失败，不重试）；返回影响行数。 */
    int markFailed(@Param("deliveryId") String deliveryId,
                   @Param("lastError") String lastError);

    /** 瞬态失败记账：attempts+1、last_error（独立事务调用，重试回滚后仍保留）。 */
    int recordAttempt(@Param("deliveryId") String deliveryId,
                      @Param("lastError") String lastError);
}
