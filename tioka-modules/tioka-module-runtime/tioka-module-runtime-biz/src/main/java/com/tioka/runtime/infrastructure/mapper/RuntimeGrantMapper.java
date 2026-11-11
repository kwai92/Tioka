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

    /** C-17 修复：WITH RECURSIVE 查本授予及后代中仍 ACTIVE 的授予 ID。 */
    List<String> findReapDescendants(@Param("grantId") String grantId);

    /**
     * WITH RECURSIVE 按 parent_id 级联置终态；返回受影响授予 ID（ADR-039 缓存失效）。
     * C-17 修复：原签名直接返回 List（UPDATE...RETURNING），MyBatis 对 update 语句
     * 不支持集合返回（BindingException）；改为「select 查 ACTIVE 后代 → update 置终态」
     * 两步，更新行数由 update 返回，受影响 ID 由 {@link #findReapDescendants} 提供。
     */
    int reapRecursive(@Param("ids") List<String> ids, @Param("status") String status);

    /** 子 thread 全部 Turn 的 token 用量合计（MAS-03 预算回拨）。 */
    long sumThreadTokens(@Param("threadId") String threadId);

    /** 父 Turn 下仍在跑的子 Turn 数（RT-12 并行数限额）。 */
    int countActiveChildTurns(@Param("parentTurnId") String parentTurnId);

    /** 父 Turn 的子 thread ID（MAS-03 / 级联取消）。 */
    List<String> findChildThreadIds(@Param("parentTurnId") String parentTurnId);
}
