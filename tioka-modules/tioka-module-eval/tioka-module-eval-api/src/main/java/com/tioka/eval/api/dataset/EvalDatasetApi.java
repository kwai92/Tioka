package com.tioka.eval.api.dataset;

import java.util.List;
import java.util.Optional;

/**
 * 评测数据集端口（EVO-02 空壳；实现在 eval-biz）。
 */
public interface EvalDatasetApi {

    Optional<EvalDatasetView> get(String datasetId);

    List<EvalCaseView> listCases(String datasetId, int version);
}
