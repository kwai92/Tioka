package com.tioka.agent.api.publish;

import com.tioka.agent.api.spec.AgentSpec;

import java.util.List;

/** 收集全部 PublishCheck 错误后返回（AST-02）。 */
public interface PublishValidator {
    List<PublishCheck.PublishError> validate(AgentSpec spec, PublishCheck.PublishContext ctx);
}
