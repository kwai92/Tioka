package com.tioka.system.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AirGapService implements EgressGuard {
    private final boolean enabled;

    public AirGapService(@Value("${tioka.airgap.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void assertAllowed(String url) {
        assertEgressAllowed(url);
    }

    public void assertEgressAllowed(String url) {
        if (enabled && url != null && url.startsWith("http") && !url.contains("localhost")) {
            throw new BizException(ErrorCode.FORBIDDEN, "空气间隙模式禁止公网出站");
        }
    }

    public boolean enabled() { return enabled; }
}
