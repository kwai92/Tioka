package com.tioka.skill.api;

import java.util.Optional;

public interface SkillQueryApi {
    Optional<SkillView> findPublished(String skillId);

    record SkillView(String id, String name, String skillMd, String description) {}
}
