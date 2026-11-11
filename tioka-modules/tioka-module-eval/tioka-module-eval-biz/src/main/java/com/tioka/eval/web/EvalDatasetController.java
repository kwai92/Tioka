package com.tioka.eval.web;

import com.tioka.eval.api.EvalApi;
import com.tioka.eval.api.dataset.EvalDatasetView;
import com.tioka.eval.application.DatasetService;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * EVO-02 数据集接口（POST /api/eval/datasets、POST /api/eval/datasets/{id}/cases）。
 * 类级条件照 EvalFeedbackController 先例：{@code tioka.eval.enabled=true}。
 */
@RestController
@RequestMapping("/api/eval/datasets")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class EvalDatasetController {

    private final DatasetService datasets;

    public EvalDatasetController(DatasetService datasets) {
        this.datasets = datasets;
    }

    public record CreateDatasetCmd(String name, String schemaJson) {}

    public record ImportCasesCmd(List<EvalApi.EvalCase> cases) {}

    @PostMapping
    @RequirePermission(Permissions.CHAT_USE)
    public EvalDatasetView create(@RequestBody CreateDatasetCmd cmd) {
        return datasets.createDataset(new EvalApi.CreateDatasetCmd(cmd.name(), cmd.schemaJson()));
    }

    @PostMapping("/{id}/cases")
    @RequirePermission(Permissions.CHAT_USE)
    public EvalApi.DatasetVersionView importCases(@PathVariable String id,
                                                  @RequestBody ImportCasesCmd cmd) {
        return datasets.importCases(id, cmd.cases());
    }
}
