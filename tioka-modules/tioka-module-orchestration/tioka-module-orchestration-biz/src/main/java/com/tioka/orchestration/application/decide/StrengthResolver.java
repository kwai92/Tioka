package com.tioka.orchestration.application.decide;

import com.tioka.orchestration.api.engine.MandateVia;
import com.tioka.orchestration.api.engine.OrchApi;

import java.util.Map;

/**
 * 事件强度（ADR-020 修订第 2 条）。
 */
public final class StrengthResolver {

    public static final String ATTR_ADDRESSED = "tioka.addressed";
    public static final String ATTR_ACTOR_IS_BOT = "tioka.actor_is_bot";
    public static final String ATTR_EXTERNAL_THREAD = "tioka.external_thread";
    public static final String ATTR_APPROVAL_REPLY = "tioka.approval_reply";

    private StrengthResolver() {
    }

    public static OrchApi.Strength resolve(Map<String, String> attrs, boolean followupHit) {
        if (attrs != null && "true".equalsIgnoreCase(attrs.get(ATTR_ACTOR_IS_BOT))) {
            return OrchApi.Strength.INFERRED;
        }
        if (followupHit) {
            return OrchApi.Strength.EXPLICIT;
        }
        if (attrs != null && "true".equalsIgnoreCase(attrs.get(ATTR_ADDRESSED))) {
            return OrchApi.Strength.EXPLICIT;
        }
        if (attrs != null && "true".equalsIgnoreCase(attrs.get(ATTR_APPROVAL_REPLY))) {
            return OrchApi.Strength.EXPLICIT;
        }
        return OrchApi.Strength.INFERRED;
    }

    public static MandateVia viaFor(Map<String, String> attrs, boolean followupHit, OrchApi.Strength strength) {
        if (followupHit) {
            return MandateVia.FOLLOWUP;
        }
        if (attrs != null && "true".equalsIgnoreCase(attrs.get(ATTR_ACTOR_IS_BOT))) {
            return MandateVia.BOT;
        }
        if (strength == OrchApi.Strength.EXPLICIT) {
            return MandateVia.ADDRESSED;
        }
        return MandateVia.ADDRESSED;
    }
}
