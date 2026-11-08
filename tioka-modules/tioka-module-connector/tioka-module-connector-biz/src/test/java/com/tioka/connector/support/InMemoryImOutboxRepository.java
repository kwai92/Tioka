package com.tioka.connector.support;

import com.tioka.connector.infrastructure.ImOutboxRepository;
import com.tioka.connector.infrastructure.persistence.ImOutboxDO;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** 单测用内存 outbox（不碰 DB）。 */
public final class InMemoryImOutboxRepository extends ImOutboxRepository {
    private final AtomicLong seq = new AtomicLong(1);
    private final List<ImOutboxDO> rows = new CopyOnWriteArrayList<>();

    public InMemoryImOutboxRepository() {
        super(null);
    }

    @Override
    public void insert(String channel, String payloadJson) {
        ImOutboxDO row = new ImOutboxDO();
        row.setId(seq.getAndIncrement());
        row.setChannel(channel);
        row.setPayloadJson(payloadJson);
        rows.add(row);
    }

    @Override
    public List<ImOutboxDO> findByChannel(String channel) {
        List<ImOutboxDO> out = new ArrayList<>();
        for (ImOutboxDO row : rows) {
            if (channel.equals(row.getChannel())) {
                out.add(row);
            }
        }
        return out;
    }

    public List<ImOutboxDO> all() {
        return List.copyOf(rows);
    }
}
