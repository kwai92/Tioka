package com.tioka.system.application.auth;

import com.tioka.framework.security.PublicApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PublicApiRegistry {
    private final RequestMappingHandlerMapping mapping;

    public PublicApiRegistry(@Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping) {
        this.mapping = mapping;
    }

    public String[] matchers() {
        List<String> paths = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            if (e.getValue().getMethodAnnotation(PublicApi.class) == null
                    && e.getValue().getBeanType().getAnnotation(PublicApi.class) == null) {
                continue;
            }
            Set<String> patternValues = e.getKey().getPatternValues();
            paths.addAll(patternValues);
        }
        // 兜底：即便扫描时序导致空，登录相关路径仍放行
        if (paths.isEmpty()) {
            return new String[]{
                    "/api/system/auth/login",
                    "/api/system/auth/refresh",
                    "/api/system/auth/oidc/*/authorize",
                    "/api/system/auth/oidc/*/callback"
            };
        }
        return paths.toArray(String[]::new);
    }
}
