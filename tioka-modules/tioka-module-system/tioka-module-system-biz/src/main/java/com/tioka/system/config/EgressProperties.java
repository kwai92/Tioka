package com.tioka.system.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "tioka.egress")
public class EgressProperties {
    public enum Mode { OPEN, ALLOW_LIST, STRICT }

    private Mode mode = Mode.OPEN;
    private List<String> allowHosts = new ArrayList<>();
    private boolean allowPrivate = true;
    private List<String> denyCidrs = List.of("169.254.0.0/16", "100.100.100.200/32");

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public List<String> getAllowHosts() { return allowHosts; }
    public void setAllowHosts(List<String> allowHosts) { this.allowHosts = allowHosts; }
    public boolean isAllowPrivate() { return allowPrivate; }
    public void setAllowPrivate(boolean allowPrivate) { this.allowPrivate = allowPrivate; }
    public List<String> getDenyCidrs() { return denyCidrs; }
    public void setDenyCidrs(List<String> denyCidrs) { this.denyCidrs = denyCidrs; }
}
