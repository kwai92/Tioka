package com.tioka.system.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.tioka.system")
@EnableConfigurationProperties({
        AuthProperties.class, OidcProperties.class, BootstrapProperties.class, EgressProperties.class, AuditProperties.class})
public class SystemAutoConfiguration {
}
