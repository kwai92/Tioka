package com.tioka.model.application.routing;

import com.tioka.model.api.routing.RoutingDecider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * MH-06：决策器注册表（按 spec decision.deciderId 解析到 Spring 装配的 {@link RoutingDecider}）。
 * {@code http} 系 id（http / http:{name}）统一落到外部 HTTP 决策器（endpoint 由配置指定）。
 */
@Component
public class RoutingDeciderRegistry {

    public static final String ID_HEURISTIC = "heuristic";
    public static final String ID_LLM = "llm";
    public static final String ID_HTTP_PREFIX = "http";

    private final HeuristicRoutingDecider heuristic;
    private final LlmRoutingDecider llm;
    private final HttpRoutingDecider http;

    public RoutingDeciderRegistry(HeuristicRoutingDecider heuristic,
                                  LlmRoutingDecider llm,
                                  HttpRoutingDecider http) {
        this.heuristic = heuristic;
        this.llm = llm;
        this.http = http;
    }

    /** 未知决策器返回 null（执行侧走兜底组并写 FAIL 事件）。 */
    public RoutingDecider get(String deciderId) {
        String id = deciderId == null ? ID_HEURISTIC : deciderId.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith(ID_HTTP_PREFIX)) {
            return http;
        }
        return switch (id) {
            case ID_HEURISTIC -> heuristic;
            case ID_LLM -> llm;
            default -> null;
        };
    }

    List<RoutingDecider> all() {
        return List.of(heuristic, llm, http);
    }
}
