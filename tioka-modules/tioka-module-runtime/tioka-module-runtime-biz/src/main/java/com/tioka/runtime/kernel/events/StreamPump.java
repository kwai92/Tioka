package com.tioka.runtime.kernel.events;

import com.tioka.runtime.config.KernelProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class StreamPump {
    private static final Logger log = LoggerFactory.getLogger(StreamPump.class);

    private final StringRedisTemplate redis;
    private final EventStream streams;
    private final KernelProperties props;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Subscription>> subs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> readPositions = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService sendPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "sse-send");
        t.setDaemon(true);
        return t;
    });
    private final Thread pumpThread;

    public StreamPump(StringRedisTemplate redis, EventStream streams, KernelProperties props) {
        this.redis = redis;
        this.streams = streams;
        this.props = props;
        this.pumpThread = new Thread(this::loop, "rt-stream-pump");
        this.pumpThread.setDaemon(true);
        this.pumpThread.start();
        Thread heartbeat = new Thread(this::heartbeatLoop, "rt-stream-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public boolean tryAcquireConnection() {
        int max = props.getEvents().getMaxConnections();
        for (;;) {
            int cur = connections.get();
            if (cur >= max) {
                return false;
            }
            if (connections.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    public void releaseConnection() {
        connections.decrementAndGet();
    }

    public void subscribe(String threadId, String afterEntryId, EventId afterEventId, SseEmitter emitter) {
        Subscription sub = new Subscription(emitter, afterEventId);
        subs.computeIfAbsent(threadId, k -> new CopyOnWriteArrayList<>()).add(sub);
        readPositions.putIfAbsent(threadId, afterEntryId == null || afterEntryId.isBlank() ? "0-0" : afterEntryId);
        emitter.onCompletion(() -> remove(threadId, sub));
        emitter.onTimeout(() -> remove(threadId, sub));
        emitter.onError(e -> remove(threadId, sub));
        lock.lock();
        try {
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void remove(String threadId, Subscription sub) {
        CopyOnWriteArrayList<Subscription> list = subs.get(threadId);
        if (list != null) {
            list.remove(sub);
            if (list.isEmpty()) {
                subs.remove(threadId, list);
                readPositions.remove(threadId);
            }
        }
        releaseConnection();
    }

    private void loop() {
        while (running.get()) {
            try {
                List<String> keys = new ArrayList<>(subs.keySet());
                if (keys.isEmpty()) {
                    lock.lock();
                    try {
                        wake.awaitNanos(Duration.ofSeconds(1).toNanos());
                    } finally {
                        lock.unlock();
                    }
                    continue;
                }
                StreamOffset<String>[] offsets = new StreamOffset[keys.size()];
                for (int i = 0; i < keys.size(); i++) {
                    String tid = keys.get(i);
                    String pos = readPositions.getOrDefault(tid, "0-0");
                    offsets[i] = StreamOffset.create(streams.streamKey(tid),
                            org.springframework.data.redis.connection.stream.ReadOffset.from(pos));
                }
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        StreamReadOptions.empty().count(200).block(Duration.ofMillis(200)),
                        offsets);
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> rec : records) {
                    String key = rec.getStream();
                    if (key == null || !key.startsWith("rt:events:")) {
                        continue;
                    }
                    String threadId = key.substring("rt:events:".length());
                    readPositions.put(threadId, rec.getId().getValue());
                    Map<Object, Object> v = rec.getValue();
                    String eid = str(v.get("eid"));
                    String type = str(v.get("type"));
                    String data = str(v.get("data"));
                    dispatch(threadId, eid, type, data);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.debug("stream pump tick: {}", e.toString());
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void dispatch(String threadId, String eid, String type, String data) {
        CopyOnWriteArrayList<Subscription> list = subs.get(threadId);
        if (list == null || list.isEmpty()) {
            return;
        }
        EventId eventId = eid.isBlank() ? null : EventId.parse(eid);
        for (Subscription sub : list) {
            if (eventId != null && eventId.compareTo(sub.sent) <= 0 && !type.startsWith("turn.")) {
                continue;
            }
            if (eventId != null) {
                sub.sent = eventId;
            }
            sendPool.execute(() -> send(sub.emitter, eid, type, data));
        }
    }

    void send(SseEmitter emitter, String eid, String type, String data) {
        try {
            SseEmitter.SseEventBuilder b = SseEmitter.event().name(type).data(data);
            if (eid != null && !eid.isBlank()) {
                b = b.id(eid);
            }
            emitter.send(b);
        } catch (IOException | IllegalStateException e) {
            emitter.complete();
        }
    }

    private void heartbeatLoop() {
        while (running.get()) {
            try {
                Thread.sleep(props.getEvents().getSseHeartbeat().toMillis());
                for (CopyOnWriteArrayList<Subscription> list : subs.values()) {
                    for (Subscription sub : list) {
                        sendPool.execute(() -> {
                            try {
                                sub.emitter.send(SseEmitter.event().comment("ping"));
                            } catch (Exception e) {
                                sub.emitter.complete();
                            }
                        });
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        pumpThread.interrupt();
        sendPool.shutdownNow();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static final class Subscription {
        final SseEmitter emitter;
        volatile EventId sent;

        Subscription(SseEmitter emitter, EventId sent) {
            this.emitter = emitter;
            this.sent = sent == null ? EventId.parse("") : sent;
        }
    }
}
