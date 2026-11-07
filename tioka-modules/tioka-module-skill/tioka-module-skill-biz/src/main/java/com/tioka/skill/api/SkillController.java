package com.tioka.skill.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.skill.application.SkillService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/skills")
@RequirePermission(Permissions.SKILL_WRITE)
public class SkillController {
    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public ApiResponse<List<SkillService.SkillPackage>> list() {
        return ApiResponse.ok(skillService.list());
    }

    @PostMapping("/import")
    @Audited(action = "skill.import", resourceType = "skill")
    public ApiResponse<SkillService.SkillPackage> importZip(@RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(skillService.importZip(file.getBytes()));
    }

    @PostMapping("/{id}/publish")
    @Audited(action = "skill.publish", resourceType = "skill", resourceIdArg = "#id")
    public ApiResponse<SkillService.SkillPackage> publish(@PathVariable String id) {
        return ApiResponse.ok(skillService.publish(id));
    }
}
