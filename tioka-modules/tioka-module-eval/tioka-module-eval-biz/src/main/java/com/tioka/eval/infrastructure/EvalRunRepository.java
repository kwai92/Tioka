package com.tioka.eval.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.eval.infrastructure.mapper.EvalResultMapper;
import com.tioka.eval.infrastructure.mapper.EvalRunMapper;
import com.tioka.eval.infrastructure.persistence.EvalResultDO;
import com.tioka.eval.infrastructure.persistence.EvalRunDO;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * EVO-02 评测运行 / 逐条结果存取。eval_result 只增不改。
 * 类级条件同 EvalDatasetRepository（C-8 教训）。
 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class EvalRunRepository {

    private final EvalRunMapper runs;
    private final EvalResultMapper results;

    public EvalRunRepository(EvalRunMapper runs, EvalResultMapper results) {
        this.runs = runs;
        this.results = results;
    }

    public void insertRun(EvalRunDO row) {
        runs.insert(row);
    }

    public EvalRunDO findRun(String runId) {
        return runs.selectById(runId);
    }

    public void finishRun(String runId, String status, String summaryJson) {
        EvalRunDO patch = new EvalRunDO();
        patch.setId(runId);
        patch.setStatus(status);
        patch.setSummaryJson(summaryJson);
        patch.setEndedAt(java.time.LocalDateTime.now());
        runs.updateById(patch);
    }

    public void insertResult(EvalResultDO row) {
        results.insert(row);
    }

    public List<EvalResultDO> listResults(String runId) {
        return results.selectList(Wrappers.<EvalResultDO>lambdaQuery()
                .eq(EvalResultDO::getRunId, runId)
                .orderByAsc(EvalResultDO::getId));
    }
}
