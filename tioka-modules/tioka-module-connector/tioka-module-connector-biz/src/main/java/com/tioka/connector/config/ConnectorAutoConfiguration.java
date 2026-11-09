package com.tioka.connector.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.tioka.connector")
@EnableConfigurationProperties(OAuthProviderProperties.class)
public class ConnectorAutoConfiguration {
}
