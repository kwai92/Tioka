package com.tioka.agent.api.publish;

import java.util.List;

/**
 * AgentSpec 段校验器注册表（P3-00b / K9）。
 * 各段校验器以 {@link PublishCheck} 注册；发布时按注册顺序执行，行为与原先 List 注入一致。
 */
public interface AgentSpecValidatorRegistry {

    /** 已排序的校验器快照。 */
    List<PublishCheck> validators();
}
