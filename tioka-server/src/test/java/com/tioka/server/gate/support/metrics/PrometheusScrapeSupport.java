package com.tioka.server.gate.support.metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ENG-04d：闸门环境抓取双实例指标端点（优先 Prometheus 文本，回退 Actuator metrics）。
 * <p>供 MH-10 / FLW-14 断言复用；本包只做可达与非空校验。
 */
public final class PrometheusScrapeSupport {

    public record Scrape(String url, int status, String body, boolean prometheusFormat) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Scrape scrape(String instanceBaseUrl) throws Exception {
        String base = instanceBaseUrl.replaceAll("/$", "");
        Scrape prom = get(base + "/actuator/prometheus");
        if (prom.status() == 200 && looksLikePrometheus(prom.body())) {
            return new Scrape(prom.url(), prom.status(), prom.body(), true);
        }
        Scrape metrics = get(base + "/actuator/metrics");
        if (metrics.status() != 200) {
            throw new IllegalStateException("metrics scrape failed: prometheus="
                    + prom.status() + " metrics=" + metrics.status());
        }
        return new Scrape(metrics.url(), metrics.status(), metrics.body(), false);
    }

    public Map<String, Scrape> scrapeBoth(String baseUrlA, String baseUrlB) throws Exception {
        Map<String, Scrape> out = new LinkedHashMap<>();
        out.put("A", scrape(baseUrlA));
        out.put("B", scrape(baseUrlB));
        return out;
    }

    private Scrape get(String url) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        return new Scrape(url, resp.statusCode(), resp.body() == null ? "" : resp.body(), false);
    }

    static boolean looksLikePrometheus(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("# HELP") || body.contains("# TYPE") || body.contains("jvm_");
    }
}
