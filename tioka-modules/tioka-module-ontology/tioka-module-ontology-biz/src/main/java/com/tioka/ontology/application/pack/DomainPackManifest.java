package com.tioka.ontology.application.pack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 领域包 jar 内 {@code tioka-plugin.json}（FND-12 清单 + ONT-05 扩展字段）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainPackManifest(
        String pluginKey,
        String packKey,
        String version,
        String spiType,
        String spiVersion,
        List<String> permissions,
        String entryClass,
        String signature,
        List<String> definitions,
        List<String> schemas,
        String prompts
) {
    public DomainPackManifest {
        if (permissions == null) {
            permissions = List.of();
        }
        if (definitions == null) {
            definitions = List.of();
        }
        if (schemas == null) {
            schemas = List.of();
        }
    }

    public String resolvedPackKey() {
        if (packKey != null && !packKey.isBlank()) {
            return packKey;
        }
        return pluginKey;
    }
}
