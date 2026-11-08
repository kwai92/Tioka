package com.tioka.model.application.gateway;

import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.domain.ModelEntities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MH-03：binding 冷却。60s 窗口内失败 ≥ 阈值 → {@code mh:cooldown:{bindingId}}；
 * 作为 {@link ModelBindingFilter} 在 resolve 时跳过冷却中的 binding。
 */
@Component
public class ModelBindingCooldownService implements ModelBindingFilter {
    private static final Logger log = LoggerFactory.getLogger(ModelBindingCooldownService.class);

    private final StringRedisTemplate redis;
    private final int failureThreshold;
    private final Duration window;
    private final Duration duration;

    public ModelBindingCooldownService(StringRedisTemplate redis,
                                  @Value("${tioka.model.cooldown.failures:5}") int failureThreshold,
                                  @Value("${tioka.model.cooldown.window:60s}") Duration window,
                                  @Value("${tioka.model.cooldown.duration:60s}") Duration duration) {
        this.redis = redis;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.window = window == null ? Duration.ofSeconds(60) : window;
        this.duration = duration == null ? Duration.ofSeconds(60) : duration;
    }

    @Override
    public boolean test(ModelCallContext ctx, ModelEntities.ModelBinding binding) {
        return binding != null && !isCooling(binding.id());
    }

    public boolean isCooling(String bindingId) {
        if (blank(bindingId)) {
            return false;
        }
        try {
            Boolean has = redis.hasKey(cooldownKey(bindingId));
            return Boolean.TRUE.equals(has);
        } catch (RuntimeException e) {
            log.warn("cooldown check failed bindingId={}: {}", bindingId, e.toString());
            return false;
        }
    }

    /** 记录一次失败；达阈值则进入冷却。 */
    public void recordFailure(String bindingId) {
        if (blank(bindingId)) {
            return;
        }
        try {
            String failKey = failKey(bindingId);
            Long n = redis.opsForValue().increment(failKey);
            if (n != null && n == 1L) {
                redis.expire(failKey, window);
            }
            if (n != null && n >= failureThreshold) {
                redis.opsForValue().set(cooldownKey(bindingId), "1", duration);
                redis.delete(failKey);
            }
        } catch (RuntimeException e) {
            log.warn("cooldown record failed bindingId={}: {}", bindingId, e.toString());
        }
    }

    public static String cooldownKey(String bindingId) {
        return "mh:cooldown:" + bindingId;
    }

    public static String failKey(String bindingId) {
        return "mh:fail:" + bindingId;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
