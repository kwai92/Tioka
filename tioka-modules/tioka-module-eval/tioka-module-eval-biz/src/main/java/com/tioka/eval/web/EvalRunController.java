package com.tioka.eval.web;

import com.tioka.eval.api.EvalApi;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * EVO-02 批次评测接口（POST /api/eval/runs、GET /api/eval/runs/{id}）。
 * 类级条件同批次装配：{@code tioka.eval.batch.enabled=true}（子开关，主开关
 * {@code tioka.eval.enabled} 由 EvalBatchAutoConfiguration 双开关兜底）。
 */
@RestController
@RequestMapping("/api/eval/runs")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval.batch", name = "enabled", havingValue = "true")
public class EvalRunController {

    private final EvalApi eval;

    public EvalRunController(EvalApi eval) {
        this.eval = eval;
    }

    /** C-18 EVO-07：trials（每条用例运行次数，null 缺省 1，1~10 越界拒绝，ADR-068 第 5 节）。 */
    public record StartRunCmd(String datasetId, Integer datasetVersion, String subjectType,
                              String subjectRef, List<String> evaluatorIds, Integer trials) {}

    @PostMapping
    @RequirePermission(Permissions.CHAT_USE)
    public Map<String, Object> start(@RequestBody StartRunCmd cmd) {
        String runId = eval.startRun(new EvalApi.StartEvalRunCmd(
                cmd.datasetId(), cmd.datasetVersion(), cmd.subjectType(),
                cmd.subjectRef(), cmd.evaluatorIds(), cmd.trials()));
        return Map.of("runId", runId);
    }

    @GetMapping("/{id}")
    @RequirePermission(Permissions.CHAT_USE)
    public EvalApi.EvalRunView get(@PathVariable String id) {
        return eval.getRun(id);
    }
}
