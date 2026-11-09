package com.tioka.runtime.infrastructure.mapper;

import com.tioka.runtime.infrastructure.persistence.RuntimeGrantDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RuntimeGrantMapper {

    int insert(RuntimeGrantDO row);

    RuntimeGrantDO findById(@Param("id") String id);

    /** 乐观锁：body_json 整体 CAS（M3-01 MAS-03 预算回拨）。 */
    int updateBodyCas(@Param("id") String id,
                      @Param("oldBody") String oldBody,
                      @Param("newBody") String newBody,
                      @Param("newHash") String newHash);

    /** WITH RECURSIVE 按 parent_id 级联置终态；返回受影响授予 ID（ADR-039 缓存失效）。 */
    List<String> reapRecursive(@Param("grantId") String grantId, @Param("status") String status);

    /** 子 thread 全部 Turn 的 token 用量合计（MAS-03 预算回拨）。 */
    long sumThreadTokens(@Param("threadId") String threadId);

    /** 父 Turn 下仍在跑的子 Turn 数（RT-12 并行数限额）。 */
    int countActiveChildTurns(@Param("parentTurnId") String parentTurnId);

    /** 父 Turn 的子 thread ID（MAS-03 / 级联取消）。 */
    List<String> findChildThreadIds(@Param("parentTurnId") String parentTurnId);
}
