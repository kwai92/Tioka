package com.tioka.system.infrastructure.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.api.spi.AuditSink;
import com.tioka.system.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 文件审计 sink：每行一个 JSON（GOV-02）；内存 recent 供运维接口与 IT 断言。
 */
@Primary
@Component
public class FileAuditSink implements AuditSink {
    private static final Logger log = LoggerFactory.getLogger(FileAuditSink.class);

    private final CopyOnWriteArrayList<Map<String, Object>> recent = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;
    private final Path filePath;

    public FileAuditSink(ObjectMapper objectMapper,
                         AuditProperties props,
                         @Value("${tioka.home:${user.home}/.tioka}") String tiokaHome) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(tiokaHome).resolve(props.getFileRelativePath()).normalize();
    }

    @Override
    public void emit(String action, String actorId, Map<String, Object> detail) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", Instant.now().toString());
        row.put("action", action == null ? "" : action);
        row.put("actorId", actorId == null ? "" : actorId);
        row.put("detail", detail == null ? Map.of() : detail);
        recent.add(row);
        if (recent.size() > 500) {
            recent.remove(0);
        }
        appendJsonLine(row);
    }

    private void appendJsonLine(Map<String, Object> row) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = objectMapper.writeValueAsString(row) + "\n";
            Files.writeString(filePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("write audit jsonl failed: {}", e.toString());
        }
    }

    public Path filePath() {
        return filePath;
    }

    @Override
    public List<Map<String, Object>> recent(int limit) {
        int n = Math.max(1, Math.min(limit, recent.size()));
        if (recent.isEmpty()) {
            emit("bootstrap", "system", Map.of("note", "audit sink ready"));
        }
        List<Map<String, Object>> copy = new ArrayList<>(recent);
        return copy.subList(Math.max(0, copy.size() - n), copy.size());
    }
}
