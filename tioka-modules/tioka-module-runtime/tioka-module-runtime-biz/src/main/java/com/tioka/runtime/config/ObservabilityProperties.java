package com.tioka.runtime.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GOV-08：轨迹页 / Observability 链接 Jaeger 的 URL 模板。
 * 占位符：{@code {traceId}}。
 */
@ConfigurationProperties(prefix = "tioka.observability")
public class ObservabilityProperties {

    private boolean enabled;
    private String serviceName = "tioka";
    /** 例：http://localhost:16686/trace/{traceId} */
    private String traceUrlTemplate = "http://localhost:16686/trace/{traceId}";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getTraceUrlTemplate() {
        return traceUrlTemplate;
    }

    public void setTraceUrlTemplate(String traceUrlTemplate) {
        this.traceUrlTemplate = traceUrlTemplate;
    }

    public String buildTraceUrl(String traceparent) {
        if (traceUrlTemplate == null || traceUrlTemplate.isBlank()
                || traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 2 || parts[1].isBlank()) {
            return null;
        }
        return traceUrlTemplate.replace("{traceId}", parts[1]);
    }
}
