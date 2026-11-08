package com.tioka.runtime.kernel.child;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * WAITING_CHILD 超时：写失败 tool_result 并唤醒（RT-09）。
 */
@Component
public class ChildTimeoutSweeper {
    private static final Logger log = LoggerFactory.getLogger(ChildTimeoutSweeper.class);

    private final TurnRepository turns;
    private final ItemRepository items;
    private final ChildResumeService resume;
    private final PrincipalHolder principals;
    private final ObjectMapper mapper;

    public ChildTimeoutSweeper(TurnRepository turns,
                               ItemRepository items,
                               ChildResumeService resume,
                               PrincipalHolder principals,
                               ObjectMapper mapper) {
        this.turns = turns;
        this.items = items;
        this.resume = resume;
        this.principals = principals;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${tioka.runtime.child-timeout-sweeper-interval-ms:15000}")
    public void sweep() {
        principals.runAs(SystemPrincipals.system(), () -> {
            Instant now = Instant.now();
            for (RuntimeTurnDO turn : turns.findWaitingChild(50)) {
                List<RuntimeItemDO> list = items.listByTurn(turn.getId());
                for (RuntimeItemDO i : list) {
                    if (!ItemType.TOOL_RESULT.wire().equals(i.getType())) {
                        continue;
                    }
                    JsonNode root = readTree(i.getContentJson());
                    if (root == null || !root.has("pending") || root.has("resolves")) {
                        continue;
                    }
                    String callId = root.path("callId").asText(null);
                    String timeoutAt = root.path("pending").path("timeoutAt").asText(null);
                    if (callId == null || timeoutAt == null) {
                        continue;
                    }
                    Instant deadline;
                    try {
                        deadline = Instant.parse(timeoutAt);
                    } catch (Exception e) {
                        continue;
                    }
                    if (now.isBefore(deadline)) {
                        continue;
                    }
                    boolean done = resume.resume(turn.getId(), callId, false,
                            "子任务超时", "CHILD_TIMEOUT");
                    if (done) {
                        log.info("ChildTimeoutSweeper timed out turn={} callId={}", turn.getId(), callId);
                    }
                }
            }
            return null;
        });
    }

    private JsonNode readTree(String json) {
        try {
            return json == null || json.isBlank() ? null : mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
