package com.tioka.server.gate.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 闸门：订阅 Runtime SSE，收集 id / event / data。 */
public final class SseCollector implements AutoCloseable {
    public record Frame(String id, String event, String data) {}

    private final List<Frame> frames;
    private final AtomicBoolean done;
    private final Future<?> future;

    private SseCollector(List<Frame> frames, AtomicBoolean done, Future<?> future) {
        this.frames = frames;
        this.done = done;
        this.future = future;
    }

    public static SseCollector subscribe(String baseUrl, String token, String workspaceId,
                                         String threadId, String lastEventId) {
        List<Frame> frames = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicReference<String> pendingId = new AtomicReference<>();
        AtomicReference<String> pendingEvent = new AtomicReference<>();
        StringBuilder pendingData = new StringBuilder();

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        StringBuilder uri = new StringBuilder(baseUrl)
                .append("/api/runtime/threads/").append(threadId).append("/events");
        if (lastEventId != null && !lastEventId.isBlank()) {
            uri.append("?after=").append(lastEventId);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(uri.toString()))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", workspaceId)
                .header("Accept", "text/event-stream")
                .GET();
        if (lastEventId != null && !lastEventId.isBlank()) {
            b.header("Last-Event-ID", lastEventId);
        }
        Future<?> future = client.sendAsync(b.build(), HttpResponse.BodyHandlers.fromLineSubscriber(
                new Flow.Subscriber<>() {
                    private Flow.Subscription subscription;

                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        this.subscription = s;
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(String line) {
                        if (line == null || line.isEmpty()) {
                            if (pendingEvent.get() != null || pendingData.length() > 0) {
                                frames.add(new Frame(pendingId.get(), pendingEvent.get(), pendingData.toString()));
                                pendingId.set(null);
                                pendingEvent.set(null);
                                pendingData.setLength(0);
                            }
                            return;
                        }
                        if (line.startsWith("id:")) {
                            pendingId.set(line.substring(3).trim());
                        } else if (line.startsWith("event:")) {
                            pendingEvent.set(line.substring(6).trim());
                        } else if (line.startsWith("data:")) {
                            if (pendingData.length() > 0) {
                                pendingData.append('\n');
                            }
                            pendingData.append(line.substring(5).trim());
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        done.set(true);
                    }

                    @Override
                    public void onComplete() {
                        done.set(true);
                    }
                })).toCompletableFuture();
        return new SseCollector(frames, done, future);
    }

    public List<Frame> frames() {
        return List.copyOf(frames);
    }

    public List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Frame f : frames) {
            if (f.id() != null && !f.id().isBlank()) {
                out.add(f.id());
            }
        }
        return out;
    }

    public boolean hasEvent(String type) {
        return frames.stream().anyMatch(f -> type.equals(f.event()));
    }

    public void awaitEvent(String type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (hasEvent(type)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("SSE 未收到 event=" + type + " frames=" + frames);
    }

    public String awaitIdContaining(String needle, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String id : ids()) {
                if (id.contains(needle)) {
                    return id;
                }
            }
            Thread.sleep(40);
        }
        throw new AssertionError("SSE 未收到含 " + needle + " 的 id, ids=" + ids());
    }

    @Override
    public void close() {
        future.cancel(true);
        done.set(true);
    }
}
