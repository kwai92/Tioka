package com.tioka.eval.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.eval.infrastructure.mapper.EvalCaseMapper;
import com.tioka.eval.infrastructure.mapper.EvalDatasetMapper;
import com.tioka.eval.infrastructure.mapper.EvalEvaluatorMapper;
import com.tioka.eval.infrastructure.persistence.EvalCaseDO;
import com.tioka.eval.infrastructure.persistence.EvalDatasetDO;
import com.tioka.eval.infrastructure.persistence.EvalEvaluatorDO;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * EVO-02 数据集 / 用例 / 评估器实例存取。
 * 类级条件：server 主应用 scanBasePackages=com.tioka 会全局拉起注解 bean，
 * 不加条件会在 tioka.eval.enabled=false 时硬依赖条件装配 bean 起不来（C-8 教训）。
 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class EvalDatasetRepository {

    private final EvalDatasetMapper datasets;
    private final EvalCaseMapper cases;
    private final EvalEvaluatorMapper evaluators;

    public EvalDatasetRepository(EvalDatasetMapper datasets,
                                 EvalCaseMapper cases,
                                 EvalEvaluatorMapper evaluators) {
        this.datasets = datasets;
        this.cases = cases;
        this.evaluators = evaluators;
    }

    public void insertDataset(EvalDatasetDO row) {
        datasets.insert(row);
    }

    public EvalDatasetDO findDataset(String datasetId) {
        return datasets.selectById(datasetId);
    }

    public void bumpLatestVersion(String datasetId, int version) {
        EvalDatasetDO patch = new EvalDatasetDO();
        patch.setId(datasetId);
        patch.setLatestVersion(version);
        datasets.updateById(patch);
    }

    public void insertCase(EvalCaseDO row) {
        cases.insert(row);
    }

    public List<EvalCaseDO> listCases(String datasetId, int version) {
        return cases.selectList(Wrappers.<EvalCaseDO>lambdaQuery()
                .eq(EvalCaseDO::getDatasetId, datasetId)
                .eq(EvalCaseDO::getVersion, version)
                .orderByAsc(EvalCaseDO::getId));
    }

    public List<EvalEvaluatorDO> findEvaluators(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return evaluators.selectList(Wrappers.<EvalEvaluatorDO>lambdaQuery()
                .in(EvalEvaluatorDO::getId, ids));
    }
}
