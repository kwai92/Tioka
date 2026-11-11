package com.tioka.eval.web;

import com.tioka.eval.api.TrajectoryExportApi;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * EVO-01 轨迹导出接口（按 Turn 或时间范围导出 JSON）。
 * CONFIDENTIAL 及以上数据标签需空间管理员权限——由 TrajectoryExportService 动态校验。
 */
@RestController
@RequestMapping("/api/eval/trajectory/export")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "tioka.eval", name = "enabled", havingValue = "true")
public class TrajectoryExportController {

    private final TrajectoryExportApi exporter;

    public TrajectoryExportController(TrajectoryExportApi exporter) {
        this.exporter = exporter;
    }

    public record ExportCmd(String threadId, java.util.List<String> turnIds,
                            java.time.Instant from, java.time.Instant to) {}

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RequirePermission(Permissions.CHAT_USE)
    public String export(@RequestBody ExportCmd cmd) {
        return exporter.export(new TrajectoryExportApi.ExportRequest(
                cmd.threadId(), cmd.turnIds(), cmd.from(), cmd.to()));
    }
}
