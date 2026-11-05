package com.tioka.skill.api.mq;

/**
 * MQ Topic/Tag 常量（禁止在 biz 内硬编码字符串）。
 */
public final class SkillMqTopics {

    public static final String TOPIC = "tioka-skill";

    public static final String TAG_SKILL_IMPORTED = "skill.imported";
    public static final String TAG_SKILL_PUBLISHED = "skill.published";
    public static final String TAG_BINDING_CHANGED = "binding.changed";

    private SkillMqTopics() {
    }
}
