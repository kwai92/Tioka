package com.tioka.system.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class RestAccessDenied implements AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public RestAccessDenied(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.getHttpStatus());
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.fail(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage()));
    }
}
