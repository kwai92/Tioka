package com.tioka.eval.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.eval.infrastructure.mapper.EvalFeedbackMapper;
import com.tioka.eval.infrastructure.persistence.EvalFeedbackDO;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * EVO-01 反馈存取。UNIQUE (turn_id, user_id) 语义：插入冲突视为已存在（幂等）。
 * 类级条件：server 主应用 scanBasePackages=com.tioka 会全局拉起注解 bean，
 * 不加条件会在 tioka.eval.enabled=false 时硬依赖条件装配 bean 起不来（C-8 教训）。
 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class EvalFeedbackRepository {

    private final EvalFeedbackMapper mapper;

    public EvalFeedbackRepository(EvalFeedbackMapper mapper) {
        this.mapper = mapper;
    }

    /** @return true=新写入；false=已存在（UNIQUE 冲突，保留原反馈）。 */
    public boolean insertIfAbsent(EvalFeedbackDO row) {
        try {
            mapper.insert(row);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public EvalFeedbackDO find(String turnId, String userId) {
        return mapper.selectOne(Wrappers.<EvalFeedbackDO>lambdaQuery()
                .eq(EvalFeedbackDO::getTurnId, turnId)
                .eq(EvalFeedbackDO::getUserId, userId));
    }
}
