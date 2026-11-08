package com.tioka.runtime.kernel.domain;

import com.tioka.runtime.api.context.ContextSegmentProvider;
import com.tioka.runtime.api.context.ContextZone;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文段提供者注册表（P3-00b / K1a）。
 * 无提供者时为空；ContextBuilder 在空表时走原 item→messages 路径，行为不变。
 */
@Component
public class ContextSegmentRegistry {

    private final List<ContextSegmentProvider> providers;

    public ContextSegmentRegistry(List<ContextSegmentProvider> providers) {
        List<ContextSegmentProvider> sorted = new ArrayList<>(providers == null ? List.of() : providers);
        sorted.sort(Comparator
                .comparing((ContextSegmentProvider p) -> p.zone().ordinal())
                .thenComparingInt(ContextSegmentProvider::order));
        this.providers = List.copyOf(sorted);
    }

    public boolean isEmpty() {
        return providers.isEmpty();
    }

    public List<ContextSegmentProvider> providers() {
        return providers;
    }

    public List<ContextSegmentProvider> forZone(ContextZone zone) {
        return providers.stream().filter(p -> p.zone() == zone).toList();
    }

    /** 按区分组的只读视图（调试 / 后续 RT-10 组装用）。 */
    public Map<ContextZone, List<ContextSegmentProvider>> byZone() {
        Map<ContextZone, List<ContextSegmentProvider>> map = new EnumMap<>(ContextZone.class);
        for (ContextZone z : ContextZone.values()) {
            map.put(z, forZone(z));
        }
        return Map.copyOf(map);
    }
}
