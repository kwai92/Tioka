package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.events.EventStream;
import com.tioka.runtime.kernel.events.RuntimeEventFactory;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.AuthService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("it")
class ThreadEventsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired EventStream streams;
    @Autowired RuntimeEventFactory events;
    @Autowired ItemRepository items;
    @Autowired RuntimeSessionRepository sessions;
    @Autowired AuthService auth;
    @Autowired PrincipalHolder principals;

    @Test
    void resumeFromMiddleOfDelta() throws Exception {
        AuthService.LoginResult login = auth.login("admin",
                IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        String threadId = Ids.newId("thr");
        seedSession(threadId, login.user().id());

        String itemId = Ids.newId("itm");
        String cur = streams.publish(threadId, "5.0", RuntimeEventType.ITEM_CREATED.wire(),
                events.itemCreated(threadId, "turn_x", itemId, 5, ItemType.MESSAGE.wire()));
        streams.setCurrentEntry(threadId, cur);
        for (int i = 1; i <= 4; i++) {
            streams.publish(threadId, "5." + i, RuntimeEventType.ITEM_DELTA.wire(),
                    events.itemDelta(threadId, "turn_x", itemId, i, "d" + i, null));
        }
        var completed = new com.tioka.runtime.kernel.infrastructure.persistence.RuntimeItemDO();
        completed.setId(itemId);
        completed.setSessionId(threadId);
        completed.setTurnId("turn_x");
        completed.setSeq(5L);
        completed.setStep(0);
        completed.setType(ItemType.MESSAGE.wire());
        completed.setRole("assistant");
        completed.setContentJson("{\"text\":\"done\"}");
        streams.publish(threadId, "5", RuntimeEventType.ITEM_COMPLETED.wire(),
                events.itemCompleted(threadId, completed));

        List<String> ids = collectEventIds(login.accessToken(), threadId, "5.3", 1200);
        assertFalse(ids.stream().anyMatch(id -> id.equals("5.0") || id.equals("5.1") || id.equals("5.2") || id.equals("5.3")));
        assertTrue(ids.contains("5.4"), "应收到 5.4: " + ids);
        assertTrue(ids.contains("5"), "应收到 completed 5: " + ids);
    }

    @Test
    void resumeAfterCompletion() throws Exception {
        AuthService.LoginResult login = auth.login("admin",
                IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        String threadId = Ids.newId("thr");
        seedSession(threadId, login.user().id());

        principals.runAs(user(login.user().id()), () -> {
            for (long seq = 1; seq <= 3; seq++) {
                items.insertReserved(seq, threadId, "turn_done", 0,
                        ItemType.MESSAGE.wire(), "assistant", "{\"text\":\"m" + seq + "\"}", null);
            }
            RuntimeSessionDO s = sessions.find(threadId);
            s.setLastItemSeq(3L);
            sessions.upsert(s);
        });

        List<String> ids = collectEventIds(login.accessToken(), threadId, "0", 1200);
        assertTrue(ids.contains("1"), ids.toString());
        assertTrue(ids.contains("2"), ids.toString());
        assertTrue(ids.contains("3"), ids.toString());
    }

    private void seedSession(String threadId, String ownerId) {
        principals.runAs(user(ownerId), () -> {
            RuntimeSessionDO row = new RuntimeSessionDO();
            row.setId(threadId);
            row.setStatus("ACTIVE");
            row.setAgentVersionId("av_x");
            row.setRoutingPlanId("rp_x");
            row.setLastItemSeq(0L);
            row.setOrigin("chat");
            row.setWorkspaceId("ws_default");
            row.setOwnerId(ownerId);
            sessions.upsert(row);
        });
    }

    private static Principal user(String uid) {
        return new Principal(uid, "ws_default", PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }

    private List<String> collectEventIds(String token, String threadId, String lastEventId, long waitMs)
            throws Exception {
        List<String> ids = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/runtime/threads/" + threadId
                        + "/events?after=" + lastEventId))
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", "ws_default")
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var future = client.sendAsync(req, HttpResponse.BodyHandlers.fromLineSubscriber(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;
            @Override public void onSubscribe(Flow.Subscription s) {
                this.subscription = s;
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(String line) {
                if (line.startsWith("id:")) {
                    ids.add(line.substring(3).trim());
                }
            }
            @Override public void onError(Throwable t) { done.set(true); }
            @Override public void onComplete() { done.set(true); }
        }));
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline && ids.size() < 2) {
            Thread.sleep(50);
        }
        future.cancel(true);
        return new ArrayList<>(ids);
    }
}
