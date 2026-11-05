package com.tioka.skill.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.skill.application.SkillService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/skills")
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
    public ApiResponse<SkillService.SkillPackage> importZip(@RequestParam("file") MultipartFile file) throws Exception {
        return ApiResponse.ok(skillService.importZip(file.getBytes()));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<SkillService.SkillPackage> publish(@PathVariable String id) {
        return ApiResponse.ok(skillService.publish(id));
    }
}
