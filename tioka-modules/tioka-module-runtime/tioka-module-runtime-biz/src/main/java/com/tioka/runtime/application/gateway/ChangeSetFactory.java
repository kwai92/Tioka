package com.tioka.runtime.application.gateway;

import com.tioka.runtime.api.spi.ChangeOp;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.api.spi.ChangeSet.Change;
import com.tioka.runtime.api.spi.ChangeSet.Totals;
import com.tioka.runtime.api.spi.ToolInvocation;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 非事务型写工具：按参数推断变更单（estimated=true）。事务型走 {@code TransactionalTool#preview}。
 */
@Component
public class ChangeSetFactory {

    private static final Instant DEFAULT_VALID = Instant.EPOCH.plusSeconds(365L * 24 * 3600);

    public ChangeSet estimatedFromArgs(String toolName, ToolInvocation inv) {
        Map<String, Object> args = inv == null || inv.arguments() == null ? Map.of() : inv.arguments();
        List<Change> changes = new ArrayList<>();
        Map<String, Object> after = new LinkedHashMap<>(args);
        changes.add(new Change("tool", toolName == null ? "" : toolName, ChangeOp.UPDATE, Map.of(), after));
        String summary = "调用 " + toolName + "（推断）";
        Totals totals = new Totals(1, null, null, Set.of("tool"));
        return new ChangeSet(summary, changes, totals, null, true, Instant.now().plusSeconds(600));
    }

    /** 测试 / 占位：空变更单。 */
    public ChangeSet empty(String summary) {
        return new ChangeSet(summary == null ? "" : summary, List.of(),
                new Totals(0, null, null, Set.of()), null, true, DEFAULT_VALID);
    }
}
