package com.tioka.orchestration.watcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.orchestration.api.engine.OrchTools;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 从 Turn 的 tool_call Item 中提取最后一次 {@code orch.receipt} 参数。
 */
@Component
public class ReceiptExtractor {

    private final RuntimeApi runtime;
    private final ObjectMapper mapper;

    public ReceiptExtractor(RuntimeApi runtime, ObjectMapper mapper) {
        this.runtime = runtime;
        this.mapper = mapper;
    }

    public Optional<JsonNode> extract(String threadId, String turnId) {
        if (threadId == null || turnId == null) {
            return Optional.empty();
        }
        List<RuntimeApi.ItemView> items = runtime.listItems(threadId, 0, 500);
        JsonNode last = null;
        for (RuntimeApi.ItemView item : items) {
            if (!turnId.equals(item.turnId())) {
                continue;
            }
            if (item.type() != ItemType.TOOL_CALL) {
                continue;
            }
            Object name = item.content() == null ? null : item.content().get("name");
            if (!OrchTools.RECEIPT.equals(String.valueOf(name))) {
                continue;
            }
            Object args = item.content().get("arguments");
            if (args == null) {
                continue;
            }
            last = mapper.valueToTree(args);
        }
        return Optional.ofNullable(last);
    }
}
