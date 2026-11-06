package com.tioka.agent.api.publish;

import com.tioka.agent.api.spec.AgentSpec;

import java.util.List;

/**
 * 发布校验项（AST-02 实现具体检查；AST-01 仅提供扩展点）。
 */
public interface PublishCheck {
    List<PublishError> check(AgentSpec spec, PublishContext ctx);

    record PublishError(String path, String code, String message) {}

    record PublishContext(String agentId, String publisherUserId, boolean rollback) {}
}
