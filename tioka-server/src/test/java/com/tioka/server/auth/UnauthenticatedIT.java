package com.tioka.server.auth;

import com.tioka.framework.security.PublicApi;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class UnauthenticatedIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @Test
    void allApiReturn401() throws Exception {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            if (e.getValue().getMethodAnnotation(PublicApi.class) != null
                    || e.getValue().getBeanType().getAnnotation(PublicApi.class) != null) {
                continue;
            }
            Set<String> patterns = e.getKey().getPatternValues();
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "x");
                RequestMethod method = methods.isEmpty() ? RequestMethod.GET : methods.iterator().next();
                mockMvc.perform(request(method.asHttpMethod(), path))
                        .andExpect(status().isUnauthorized());
            }
        }
    }

    @Test
    void queryTokenIgnored() throws Exception {
        mockMvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/system/auth/me")
                        .queryParam("token", "tka_fake"))
                .andExpect(status().isUnauthorized());
    }
}
