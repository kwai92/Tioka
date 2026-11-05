package com.tioka.runtime.application;

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class CodeInterpreterService {
    public Map<String, Object> execute(String language, String code) {
        String artifactId = "art_" + UUID.randomUUID().toString().substring(0, 8);
        return Map.of(
                "language", language,
                "stdout", "executed:" + (code == null ? "" : code.hashCode()),
                "artifactId", artifactId,
                "downloadPath", "/api/runtime/artifacts/" + artifactId
        );
    }
}
