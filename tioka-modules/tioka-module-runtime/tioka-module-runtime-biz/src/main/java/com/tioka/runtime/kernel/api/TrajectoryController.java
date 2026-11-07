package com.tioka.runtime.kernel.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.runtime.api.kernel.TrajectoryApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HCI-07b：轨迹查询。
 */
@RestController
@RequestMapping("/api/runtime")
@RequirePermission(Permissions.CHAT_USE)
public class TrajectoryController {

    private final TrajectoryApi trajectory;

    public TrajectoryController(TrajectoryApi trajectory) {
        this.trajectory = trajectory;
    }

    @GetMapping("/threads/{id}/turns")
    public ApiResponse<List<TrajectoryApi.TurnSummary>> turns(
            @PathVariable String id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(trajectory.turns(id, page, size));
    }

    @GetMapping("/turns/{id}/trajectory")
    public ApiResponse<TrajectoryApi.Trajectory> trajectory(@PathVariable String id) {
        return ApiResponse.ok(trajectory.turn(id));
    }
}
