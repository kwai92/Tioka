package com.tioka.system.application.egress;

import com.sun.net.httpserver.HttpServer;
import com.tioka.system.api.obs.ExtInteractionRecorder;
import com.tioka.system.api.spi.EgressGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-04a：EgressClient 出网台账接线离线单测（JDK 内置 HttpServer，localhost 离线）。
 * 覆盖：OK / ERROR / 守卫拒绝不写台账 / recorder 缺失静默 / digest 确定性。
 */
class DefaultEgressClientLedgerTest {

    private EgressGuard guard;
    private ObjectProvider<ExtInteractionRecorder> provider;
    private List<ExtInteractionRecorder.ExtInteractionRecord> captured;
    private HttpServer server;
    private int port;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        guard = mock(EgressGuard.class);
        captured = new CopyOnWriteArrayList<>();
        ExtInteractionRecorder recorder = captured::add;
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "hello-ledger".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boom", exchange -> {
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @SuppressWarnings("unchecked")
    private DefaultEgressClient client() {
        return new DefaultEgressClient(guard, provider);
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    @Test
    void successfulSendWritesHttpOutLedgerRow() throws Exception {
        HttpResponse<String> resp = client().send(get("http://127.0.0.1:" + port + "/ok?secret=1"));
        assertEquals(200, resp.statusCode());

        ArgumentCaptor<ExtInteractionRecorder.ExtInteractionRecord> captor =
                ArgumentCaptor.forClass(ExtInteractionRecorder.ExtInteractionRecord.class);
        // record 是无返回方法，用同一个 List 直接断言
        assertEquals(1, captured.size());
        ExtInteractionRecorder.ExtInteractionRecord row = captured.get(0);
        assertEquals(ExtInteractionRecorder.Direction.OUT, row.direction());
        assertEquals("http", row.kind());
        assertEquals("127.0.0.1", row.counterparty());
        // endpoint 不含查询串（secret 不落台账）
        assertEquals("127.0.0.1/ok", row.endpoint());
        assertEquals("GET", row.operation());
        assertEquals(ExtInteractionRecorder.Status.OK, row.status());
        assertEquals(200, row.httpStatus());
        assertTrue(row.latencyMs() >= 0);
        // 请求摘要 = sha256(uri+method)，确定性
        assertEquals(DefaultEgressClient.sha256(URI.create("http://127.0.0.1:" + port + "/ok?secret=1") + "\nGET"),
                row.attrs().get("request_digest"));
        assertEquals("12", row.attrs().get("response_size"));
    }

    @Test
    void connectionRefusedWritesErrorRowAndRethrows() {
        // 关闭端口上的连接（用一个几乎肯定未监听的高位端口）
        int deadPort = 59999;
        DefaultEgressClient client = client();
        HttpRequest req = get("http://127.0.0.1:" + deadPort + "/x");

        assertThrows(Exception.class, () -> client.send(req));
        assertEquals(1, captured.size());
        ExtInteractionRecorder.ExtInteractionRecord row = captured.get(0);
        assertEquals(ExtInteractionRecorder.Status.ERROR, row.status());
        assertEquals(0L, Long.parseLong(row.attrs().get("response_size")));
    }

    @Test
    void guardDenialNeverReachesLedger() {
        doThrow(new RuntimeException("egress denied")).when(guard).assertAllowed(anyString());
        DefaultEgressClient client = client();
        assertThrows(RuntimeException.class,
                () -> client.send(get("http://127.0.0.1:" + port + "/ok")));
        assertEquals(0, captured.size(), "守卫拒绝不写台账（走审计路径）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingRecorderSilentlySkipsLedger() throws Exception {
        when(provider.getIfAvailable()).thenReturn(null);
        HttpResponse<String> resp = client().send(get("http://127.0.0.1:" + port + "/ok"));
        assertEquals(200, resp.statusCode());
        assertEquals(0, captured.size(), "recorder 缺失时出网不受影响");
    }

    @Test
    void recorderFailureDoesNotBreakEgress() throws Exception {
        AtomicReference<ExtInteractionRecorder> broken = new AtomicReference<>(r -> {
            throw new IllegalStateException("ledger boom");
        });
        when(provider.getIfAvailable()).thenAnswer(inv -> broken.get());
        HttpResponse<String> resp = client().send(get("http://127.0.0.1:" + port + "/ok"));
        assertEquals(200, resp.statusCode(), "台账写入异常不得影响出网");
    }
}
