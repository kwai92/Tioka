package com.tioka.skill.application;

import com.tioka.skill.api.SkillQueryApi;
import com.tioka.skill.infrastructure.SkillPackageRepository;
import com.tioka.skill.infrastructure.persistence.SkillPackageDO;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SkillQueryService implements SkillQueryApi {
    private final SkillPackageRepository packages;

    public SkillQueryService(SkillPackageRepository packages) {
        this.packages = packages;
    }

    @Override
    public Optional<SkillView> findPublished(String skillId) {
        SkillPackageDO row = packages.findById(skillId);
        if (row == null || !Boolean.TRUE.equals(row.getPublished())) return Optional.empty();
        String desc = firstLine(row.getSkillMd());
        return Optional.of(new SkillView(row.getId(), row.getName(), row.getSkillMd(), desc));
    }

    private static String firstLine(String md) {
        if (md == null || md.isBlank()) return "";
        String[] lines = md.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("---") && !t.startsWith("#")) return t;
            if (t.startsWith("#")) return t.replaceFirst("^#+\\s*", "");
        }
        return "";
    }
}
