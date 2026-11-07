package com.tioka.agent.api;

/**
 * 旧 agent_share 运维入口（ENG-06）。
 * 实现应抛出 NotImplemented，分享改走 ResourceAcl。
 */
public interface AgentShareApi {
    ShareView share(String agentId, String mode);

    record ShareView(String id, String agentId, String mode) {}
}
