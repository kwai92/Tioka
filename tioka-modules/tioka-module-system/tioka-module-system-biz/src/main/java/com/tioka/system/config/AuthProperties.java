package com.tioka.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tioka.auth")
public class AuthProperties {
    private Duration accessTtl = Duration.ofHours(8);
    private Duration refreshTtl = Duration.ofDays(7);
    private int loginMaxFailures = 10;

    public Duration getAccessTtl() { return accessTtl; }
    public void setAccessTtl(Duration accessTtl) { this.accessTtl = accessTtl; }
    public Duration getRefreshTtl() { return refreshTtl; }
    public void setRefreshTtl(Duration refreshTtl) { this.refreshTtl = refreshTtl; }
    public int getLoginMaxFailures() { return loginMaxFailures; }
    public void setLoginMaxFailures(int loginMaxFailures) { this.loginMaxFailures = loginMaxFailures; }
}
