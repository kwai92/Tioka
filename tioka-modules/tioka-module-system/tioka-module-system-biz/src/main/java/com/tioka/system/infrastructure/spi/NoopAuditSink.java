package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.AuditSink;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 占位实现；默认转发走 {@link FileAuditSink}（@Primary）。 */
@Component
public class NoopAuditSink implements AuditSink {
    @Override
    public void emit(String action, String actorId, Map<String, Object> detail) {
    }
}
