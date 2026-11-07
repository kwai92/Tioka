package com.tioka.system.application.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.api.audit.AuditDegradedSink;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.config.AuditProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计降级文件 sink：相对 {@code tioka.home} 只追加 JSONL（OBS-01 / ADR-072）。
 */
@Component
public class FileAuditDegradedSink implements AuditDegradedSink {
    private static final Logger log = LoggerFactory.getLogger(FileAuditDegradedSink.class);

    private final ObjectMapper objectMapper;
    private final Path filePath;
    private final AtomicLong seq = new AtomicLong();

    public FileAuditDegradedSink(ObjectMapper objectMapper,
                                 AuditProperties props,
                                 @Value("${tioka.home:${user.home}/.tioka}") String tiokaHome) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(tiokaHome).resolve(props.getDegradedRelativePath()).normalize();
    }

    @Override
    public void append(AuditEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("seq", seq.incrementAndGet());
        row.put("degradedAt", Instant.now().toString());
        row.put("event", event);
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = objectMapper.writeValueAsString(row) + "\n";
            Files.writeString(filePath, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.error("audit.write.degraded path={} action={} outcome={}",
                    filePath, event == null ? null : event.action(),
                    event == null ? null : event.outcome());
        } catch (IOException e) {
            log.error("audit.write.degraded FAILED path={}: {}", filePath, e.toString());
        }
    }

    @Override
    public Path path() {
        return filePath;
    }
}
