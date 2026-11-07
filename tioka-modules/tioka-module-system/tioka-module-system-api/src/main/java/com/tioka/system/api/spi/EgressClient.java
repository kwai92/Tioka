package com.tioka.system.api.spi;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * 出网 HTTP 唯一发送入口（47-GL §1.8）：先 {@link EgressGuard#assertAllowed}，再发请求。
 * 业务域禁止自建 {@link java.net.http.HttpClient}。
 */
public interface EgressClient {

    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;

    HttpResponse<InputStream> sendStreaming(HttpRequest request) throws IOException, InterruptedException;
}
