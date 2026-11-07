package com.tioka.knowledge.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.knowledge.application.EvalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/knowledge/eval")
@RequirePermission(Permissions.KB_READ)
public class EvalController {
    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    @PostMapping("/{kbId}")
    @Audited(action = "knowledge.eval.run", resourceType = "kb", resourceIdArg = "#kbId")
    public ApiResponse<Map<String, Object>> run(@PathVariable String kbId, @RequestBody List<Map<String, String>> dataset) {
        return ApiResponse.ok(evalService.run(kbId, dataset));
    }
}
