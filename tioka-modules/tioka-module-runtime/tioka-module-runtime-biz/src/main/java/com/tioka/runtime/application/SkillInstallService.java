package com.tioka.runtime.application;

import com.tioka.runtime.api.spi.WorkspaceFs;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 将已展开的 Skill 快照安装到工作盘并生成 prompt 片段（ADR-014 D2）。
 * Skill 内容由 Agent 侧展开后传入，避免 runtime-biz → skill-api 依赖。
 */
@Service
public class SkillInstallService {
    private final WorkspaceFs workspaceFs;

    public SkillInstallService(WorkspaceFs workspaceFs) {
        this.workspaceFs = workspaceFs;
    }

    public void install(String workspaceId, String threadId, List<SkillSnapshot> skills) {
        if (skills == null || skills.isEmpty()) return;
        for (SkillSnapshot sk : skills) {
            if (sk == null || sk.name() == null || sk.name().isBlank()) continue;
            String path = "skills/" + sk.name() + "/SKILL.md";
            workspaceFs.writeBytes(workspaceId, threadId, path,
                    sk.skillMd() == null ? new byte[0] : sk.skillMd().getBytes(StandardCharsets.UTF_8));
        }
    }

    public List<String> promptFragments(List<SkillSnapshot> skills) {
        List<String> out = new ArrayList<>();
        if (skills == null) return out;
        List<String> autoIndex = new ArrayList<>();
        for (SkillSnapshot sk : skills) {
            if (sk == null) continue;
            String mode = sk.mode() == null ? "auto" : sk.mode();
            if ("always".equalsIgnoreCase(mode)) {
                String body = sk.skillMd() == null ? "" : sk.skillMd();
                if (body.length() > 4000) body = body.substring(0, 4000) + "\n...(truncated)";
                out.add("[skill:" + sk.name() + "]\n" + body);
            } else if ("auto".equalsIgnoreCase(mode)) {
                autoIndex.add("- " + sk.name() + ": " + (sk.description() == null ? "" : sk.description())
                        + " (read skills/" + sk.name() + "/SKILL.md when needed)");
            }
            // manual: 不注入
        }
        if (!autoIndex.isEmpty()) {
            out.add("Available skills (progressive):\n" + String.join("\n", autoIndex));
        }
        return out;
    }

    public record SkillSnapshot(String skillId, String name, String mode, String description, String skillMd) {}
}
