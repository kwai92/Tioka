package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 本实例只读投影缓存（ADR-041 §2 跨实例刷新）。 */
public class ConfigProjectionCache {

    public record Entry(
            String versionId,
            int versionNo,
            JsonNode spec,
            String contentHash,
            String status) {
        ConfigVersion toVersion(ObjectRef ref) {
            return new ConfigVersion(versionId, ref, versionNo, spec, contentHash, status);
        }
    }

    private final ConcurrentHashMap<String, Entry> local = new ConcurrentHashMap<>();

    public static String key(ObjectRef ref) {
        return ref.objectType() + "\0" + ref.objectId();
    }

    public void put(ObjectRef ref, Entry entry) {
        local.put(key(ref), entry);
    }

    public Optional<Entry> get(ObjectRef ref) {
        return Optional.ofNullable(local.get(key(ref)));
    }

    public Optional<ConfigVersion> getVersion(ObjectRef ref) {
        return get(ref).map(e -> e.toVersion(ref));
    }

    public void invalidate(ObjectRef ref) {
        local.remove(key(ref));
    }

    public Set<String> keys() {
        return Set.copyOf(local.keySet());
    }

    public Map<String, Entry> snapshot() {
        return Map.copyOf(local);
    }

    public void clear() {
        local.clear();
    }
}
