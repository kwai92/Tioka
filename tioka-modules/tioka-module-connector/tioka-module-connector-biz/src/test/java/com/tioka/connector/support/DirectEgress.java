package com.tioka.connector.support;

import com.tioka.system.api.spi.EgressClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 单测直发，绕过生产 EgressGuard（对齐 knowledge DataSourceServiceTest）。 */
public final class DirectEgress implements EgressClient {
    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public HttpResponse<InputStream> sendStreaming(HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }
}
