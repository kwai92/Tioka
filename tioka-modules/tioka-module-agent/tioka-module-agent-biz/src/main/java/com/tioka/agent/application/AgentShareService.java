package com.tioka.agent.application;

import com.tioka.framework.error.NotImplemented;
import org.springframework.stereotype.Service;

/**
 * 旧 agent_share 已由 V110 删除；分享改走 {@code /api/{type}/{id}/acl}（FND-04）。
 */
@Service
public class AgentShareService {

    public Share share(String agentId, String mode) {
        throw NotImplemented.of("agent-share-legacy", "FND-04 ResourceAcl");
    }

    public record Share(String id, String agentId, String mode) {}
}
