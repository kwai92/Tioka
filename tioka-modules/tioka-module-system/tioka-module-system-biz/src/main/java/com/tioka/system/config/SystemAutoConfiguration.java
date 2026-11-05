package com.tioka.system.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.tioka.system")
@EnableConfigurationProperties({AuthProperties.class, BootstrapProperties.class})
public class SystemAutoConfiguration {
}
