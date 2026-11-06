package com.tioka.runtime.kernel.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.FailoverEvent;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.kernel.domain.LoopPorts;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MH-02：FailoverEvent → status item {@code {kind:model_failover, from, to, reason}}。
 */
@Component
public class ModelFailoverRuntimeBridge {
    private final ObjectMapper mapper;

    public ModelFailoverRuntimeBridge(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @EventListener
    public void onFailover(FailoverEvent event) {
        TurnCallScope.Holder scope = TurnCallScope.current();
        if (scope == null || scope.sink() == null || scope.ctx() == null) {
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("kind", "model_failover");
        if (event.fromModelRef() != null) {
            content.put("from", event.fromModelRef());
        }
        content.put("to", event.modelRef());
        content.put("reason", event.reason() == null ? "failover" : event.reason());
        String json;
        try {
            json = mapper.writeValueAsString(content);
        } catch (Exception e) {
            json = "{\"kind\":\"model_failover\"}";
        }
        LoopPorts.ItemSink sink = scope.sink();
        sink.complete(scope.ctx(), scope.step(), ItemType.STATUS.wire(), "system", json);
    }
}
