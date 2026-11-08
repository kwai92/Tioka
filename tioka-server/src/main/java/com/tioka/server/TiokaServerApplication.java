package com.tioka.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排除 OAuth2 Client/ResourceServer 自动装配：FND-05 仅复用 NimbusJwtDecoder，
 * 不启用 oauth2Login / 默认 JWT 资源服务器过滤器（与无状态 BearerTokenFilter 冲突）。
 */
@SpringBootApplication(
        scanBasePackages = "com.tioka",
        exclude = {
                OAuth2ClientAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class
        })
@EnableScheduling
public class TiokaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TiokaServerApplication.class, args);
    }
}
