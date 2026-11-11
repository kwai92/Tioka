package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.TiokaAuthentication;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.ItemWrite;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.RedisThreadLock;
import com.tioka.runtime.kernel.infrastructure.redis.ThreadLock;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.AuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 验收 · L1：Runtime 状态落库与按 thread_id 会话加锁。
 *
 * <p>对应 {@code delivery/后续链路实施计划.md} §3.1 L1 验收标准：
 * 进程重启后状态可恢复、同 thread 并发串行化（数据库行锁 + Redis 锁双证）、
 * 契约测试绿、{@code Last-Event-ID} 重放幂等可证、不新增 Flyway 迁移。
 *
 * <p>本批次（方案 A 冻结）重心是验收测试 + 文档化：所有被测能力均来自既有生产代码
 * （{@code runtime_turn}/{@code runtime_item} 落库、{@code RedisThreadLock}、{@code ThreadEventsService}
 * 的 DB {@code listAfterSeq} + {@code EventId} 游标续传），未改动任何 {@code src/main}。
 *
 * <p>需要运行中的 PostgreSQL + Redis（Testcontainers）；本环境若无基础设施，编译通过但运行阶段需跳过。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("it")
class P0L1AcceptanceIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @LocalServerPort int port;

    // —— 会话锁（Redis 锁证）——
    @Autowired ThreadLock sessionLock;
    @Autowired RedisThreadLock redisThreadLock;
    @Autowired KernelProperties props;
    @Autowired StringRedisTemplate redis;

    // —— 状态落库（DB 行锁证 + 续传）——
    @Autowired TurnRepository turns;
    @Autowired RuntimeThreadRepository sessions;
    @Autowired ItemRepository items;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService auth;
    @Autowired PrincipalHolder principals;

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    // —— L1 验收准则：同 thread 并发串行化（Redis 锁证）——
    @Test
    void sessionLockMutualExclusion() {
        String threadId = Ids.newId("thr");
        String turnA = Ids.newId("turn");
        String turnB = Ids.newId("turn");

        var a = sessionLock.tryAcquire(threadId, turnA);
        assertTrue(a.isPresent(), "首个 worker 应获得会话锁");
        assertTrue(sessionLock.tryAcquire(threadId, turnB).isEmpty(), "同 thread 第二 worker 不得并发持锁");

        a.get().close();
        var b = sessionLock.tryAcquire(threadId, turnB);
        assertTrue(b.isPresent(), "释放后他人可重新持锁");
        assertTrue(b.get().renew());
        b.get().close();
    }

    // —— L1 验收准则：同 thread 并发串行化（数据库行锁证）——
    @Test
    void turnAcquireOnlyOnce() throws Exception {
        String threadId = freshThread();
        RuntimeTurnDO t = queuedTurn(threadId, "cm-l1-lock");
        turns.insert(t);

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger wins = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            String worker = "w" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    var lv = turns.acquire(t.getId(), worker, Instant.now().plusSeconds(30));
                    if (lv.isPresent()) {
                        wins.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, wins.get(), "同 thread 的 Turn 租约只能被一个 worker 获取（DB 行锁串行化）");
    }

    // —— L1 验收准则：状态落库(runtime_turn/runtime_item) + Last-Event-ID 重放幂等可证 ——
    @Test
    void statePersistedAndResumableAcrossReconnect() throws Exception {
        AuthService.LoginResult login = auth.login("admin",
                IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD, "127.0.0.1");
        String threadId = Ids.newId("thr");

        // runtime_turn 落库
        RuntimeTurnDO t = queuedTurn(threadId, "cm-l1-state");
        turns.insert(t);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_turn WHERE id = ?", Long.class, t.getId()),
                "runtime_turn 行应已落库");

        // runtime_item 落库（会话 thread 必须先存在）
        seedSession(threadId, login.user().id());
        principals.runAs(user(login.user().id()), () -> {
            for (int i = 1; i <= 3; i++) {
                items.appendWithoutLease(ItemWrite.withoutLease(threadId, t.getId(), 0,
                        ItemType.MESSAGE.wire(), "assistant", "{\"text\":\"m" + i + "\"}"));
            }
        });
        assertEquals(3L, jdbc.queryForObject(
                "SELECT count(*) FROM runtime_item WHERE thread_id = ?", Long.class, threadId),
                "runtime_item 三行应已落库");

        // 新 SSE 连接带 Last-Event-ID="0" 重连 → 从 DB 重放已完成 items，幂等无重复
        List<String> ids = collectEventIds(login.accessToken(), threadId, "0", 3, 3000);
        Set<String> unique = new HashSet<>(ids);
        assertEquals(3, unique.size(), "重放不应有重复事件: " + ids);
        assertTrue(unique.contains("1") && unique.contains("2") && unique.contains("3"),
                "应从 DB 重放 seq 1/2/3: " + ids);
        assertFalse(ids.stream().anyMatch(id -> id.equals("0")), "不应重放 ≤ Last-Event-ID 的游标自身");
    }

    // ---- helpers ----

    private String freshThread() {
        SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(SystemPrincipals.system()));
        String threadId = Ids.newId("thr");
        RuntimeThreadDO s = new RuntimeThreadDO();
        s.setId(threadId);
        s.setStatus("OPEN");
        s.setAgentVersionId("agent_v1");
        s.setWorkspaceId("ws_default");
        s.setOwnerId("0");
        s.setLastItemSeq(0L);
        s.setOrigin("chat");
        s.setDepth(0);
        sessions.upsert(s);
        SecurityContextHolder.clearContext();
        return threadId;
    }

    private RuntimeTurnDO queuedTurn(String threadId, String clientMsgId) {
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId(Ids.newId("turn"));
        t.setThreadId(threadId);
        // runtime_thread 行级过滤非豁免表，nextSeq（SELECT … FOR UPDATE）须带 Principal
        t.setSeq(principals.runAs(user("1"), () -> turns.nextSeq(threadId)));
        t.setStatus("QUEUED");
        t.setOrigin("chat");
        t.setClientMsgId(clientMsgId);
        t.setRunAsJson("{\"userId\":\"1\",\"type\":\"USER\"}");
        t.setStepCount(0);
        t.setLeaseVersion(0L);
        return t;
    }

    private void seedSession(String threadId, String ownerId) {
        principals.runAs(user(ownerId), () -> {
            RuntimeThreadDO row = new RuntimeThreadDO();
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

    private List<String> collectEventIds(String token, String threadId, String lastEventId,
                                         int expectAtLeast, long waitMs) throws Exception {
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
        while (System.currentTimeMillis() < deadline && ids.size() < expectAtLeast) {
            Thread.sleep(50);
        }
        future.cancel(true);
        return new ArrayList<>(ids);
    }
}
