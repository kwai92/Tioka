package com.tioka.server.gate;

import com.tioka.server.gate.support.DualInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ENG-04：双实例启动器冒烟（不依赖完整 RuntimeApi）。
 */
@Tag("gate")
class DualInstanceSmokeIT {

    @Test
    void bothInstancesServeHealth() throws Exception {
        try (DualInstance di = DualInstance.start()) {
            assertNotEquals(0, di.portA());
            assertNotEquals(0, di.portB());
            assertNotEquals(di.portA(), di.portB());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            assertEquals(200, get(client, di.baseUrlA() + "/actuator/health").statusCode());
            assertEquals(200, get(client, di.baseUrlB() + "/actuator/health").statusCode());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
