package com.tioka.skill.api;

import java.util.Optional;

public interface SkillQueryApi {
    Optional<SkillView> findPublished(String skillId);

    /**
     * AST-02：按 skillVersionId 加 {@code FOR SHARE}。
     * M1 尚无独立 SkillVersion 表（47-GL）：{@code skillVersionId} ≡ {@code skill_package.id}；AST-05 再建表。
     */
    Optional<SkillVersionRef> lockVersion(String skillVersionId);

    record SkillView(String id, String name, String skillMd, String description) {}

    /**
     * @param hasScripts M1 暂无脚本落库，恒为 false；AST-05 补齐后据此拒绝启用
     */
    record SkillVersionRef(String id, boolean published, boolean hasScripts) {}
}

