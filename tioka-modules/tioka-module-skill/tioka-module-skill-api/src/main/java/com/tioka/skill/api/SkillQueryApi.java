package com.tioka.skill.api;

import java.util.Optional;

public interface SkillQueryApi {
    Optional<SkillView> findPublished(String skillId);

    /**
     * AST-02：按 skillVersionId 加 {@code FOR SHARE}。
     * M1 尚无独立 skill_version 表，{@code skillVersionId} 即 {@code skill_package.id}。
     */
    Optional<SkillVersionRef> lockVersion(String skillVersionId);

    record SkillView(String id, String name, String skillMd, String description) {}

    /**
     * @param hasScripts M1 暂无脚本落库，恒为 false；AST-05 补齐后据此拒绝启用
     */
    record SkillVersionRef(String id, boolean published, boolean hasScripts) {}
}

