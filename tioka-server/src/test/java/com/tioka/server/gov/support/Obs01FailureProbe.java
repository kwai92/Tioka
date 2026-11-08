package com.tioka.server.gov.support;

import com.tioka.system.api.audit.Audited;
import org.springframework.stereotype.Component;

/** OBS-01 IT：触发非 BizException 的 {@code @Audited} 失败路径。 */
@Component
public class Obs01FailureProbe {

    @Audited(action = "obs01.probe.runtime_error", resourceType = "probe")
    public void failWithRuntime() {
        throw new IllegalStateException("obs01-forced-runtime");
    }
}
