package com.tioka.connector.sdk;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存实例仓：契约测试与无 DB 场景。 */
@Component
public class InMemoryConnectorInstanceStore implements ConnectorInstanceStore {

    private final ConcurrentHashMap<String, ConnectorInstanceState> byId = new ConcurrentHashMap<>();

    @Override
    public void save(ConnectorInstanceState state) {
        byId.put(state.id(), state);
    }

    @Override
    public Optional<ConnectorInstanceState> find(String instanceId) {
        return Optional.ofNullable(byId.get(instanceId));
    }

    public void clear() {
        byId.clear();
    }
}
