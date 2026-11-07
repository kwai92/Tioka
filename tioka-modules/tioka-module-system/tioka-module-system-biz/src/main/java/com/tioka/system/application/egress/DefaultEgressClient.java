package com.tioka.system.application.egress;

import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class DefaultEgressClient implements EgressClient {
    private final EgressGuard guard;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DefaultEgressClient(EgressGuard guard) {
        this.guard = guard;
    }

    @Override
    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        guard.assertAllowed(request.uri().toString());
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public HttpResponse<InputStream> sendStreaming(HttpRequest request) throws IOException, InterruptedException {
        guard.assertAllowed(request.uri().toString());
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }
}
