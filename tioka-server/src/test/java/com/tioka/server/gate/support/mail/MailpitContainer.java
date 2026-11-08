package com.tioka.server.gate.support.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * ENG-04d：容器化 SMTP（Mailpit），HTTP API 录制收件供断言。
 * <p>内存版见 {@link SmtpCaptureStub}（单测无 Docker）；G3 冒烟用本容器。
 */
public final class MailpitContainer implements AutoCloseable {

    public static final String IMAGE = "axllent/mailpit:v1.21.2";

    private final GenericContainer<?> container;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private MailpitContainer(GenericContainer<?> container) {
        this.container = container;
    }

    @SuppressWarnings("resource")
    public static MailpitContainer start() {
        GenericContainer<?> box = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withExposedPorts(1025, 8025)
                .waitingFor(Wait.forHttp("/api/v1/info")
                        .forPort(8025)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        box.start();
        return new MailpitContainer(box);
    }

    public String smtpHost() {
        return container.getHost();
    }

    public int smtpPort() {
        return container.getMappedPort(1025);
    }

    public String apiBaseUrl() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(8025);
    }

    /** 经 Mailpit HTTP API 注入一封信（无需 JavaMail 依赖）。 */
    public void inject(String to, String subject, String body) throws Exception {
        ObjectNode msg = json.createObjectNode();
        msg.putObject("From").put("Email", "gate@tioka.local").put("Name", "gate");
        msg.putArray("To").addObject().put("Email", to).put("Name", to);
        msg.put("Subject", subject);
        msg.put("Text", body);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/send"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(msg)))
                        .timeout(Duration.ofSeconds(10))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("mailpit send: " + resp.statusCode() + " " + resp.body());
        }
    }

    public JsonNode waitForMessage(String toContains, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(apiBaseUrl() + "/api/v1/messages"))
                            .GET().timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode messages = json.readTree(resp.body()).path("messages");
                if (messages.isArray()) {
                    for (JsonNode m : messages) {
                        String to = m.path("To").toString();
                        if (to.contains(toContains)) {
                            return m;
                        }
                    }
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("no mailpit message to " + toContains + " within " + timeout);
    }

    @Override
    public void close() {
        container.stop();
    }
}
