package com.tioka.system.application.obs;

import com.tioka.system.api.obs.CounterpartyStat;
import com.tioka.system.api.obs.DestinationRow;
import com.tioka.system.api.obs.ExtInteractionQueryApi;
import com.tioka.system.api.obs.ExtInteractionView;
import com.tioka.system.infrastructure.ExtInteractionRepository;
import com.tioka.system.infrastructure.persistence.ExtInteractionQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外部交互台账查询实现（OBS-07 / ADR-074 §4）。
 *
 * <p>只读；明细查询支持「输入对方单号（{@code externalRef}）找到我方全过程」与按 {@code subject}
 * 列出某份合同被发往过哪些外部系统与模型（OBS-07 验收）；报表与统计供合规检查。
 */
@Service
public class ExtInteractionQueryService implements ExtInteractionQueryApi {

    private final ExtInteractionRepository repository;

    public ExtInteractionQueryService(ExtInteractionRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ExtInteractionView> query(Query query) {
        List<Map<String, Object>> rows = repository.query(new ExtInteractionQuery(
                blank(query.requestId()), blank(query.subject()), blank(query.counterparty()),
                blank(query.externalRef()), blank(query.kind()), blank(query.direction()),
                query.from(), query.to(), query.limit(), query.offset()));
        List<ExtInteractionView> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(new ExtInteractionView(
                    str(r.get("id")), toInstant(r.get("ts")), str(r.get("direction")), str(r.get("kind")),
                    str(r.get("counterparty")), str(r.get("endpoint")), str(r.get("operation")),
                    str(r.get("authMode")), str(r.get("actorId")), str(r.get("traceId")),
                    str(r.get("requestId")), str(r.get("subject")), str(r.get("turnId")),
                    str(r.get("runId")), str(r.get("changeSetId")), str(r.get("toolCallId")),
                    str(r.get("idempotencyKey")), str(r.get("externalRef")), str(r.get("dedupeKey")),
                    str(r.get("requestDigest")), str(r.get("responseDigest")),
                    toLong(r.get("requestSize")), toLong(r.get("responseSize")),
                    str(r.get("dataLabel")), str(r.get("status")), toInt(r.get("httpStatus")),
                    str(r.get("errorCode")), toInt(r.get("attempt")), toLong(r.get("latencyMs")),
                    str(r.get("retentionClass")), str(r.get("attrs"))));
        }
        return out;
    }

    @Override
    public List<DestinationRow> destinationReport(String kind, String dataLabel, Instant from, Instant to) {
        List<Map<String, Object>> rows = repository.destinationReport(
                blank(kind), blank(dataLabel), from, to);
        List<DestinationRow> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(new DestinationRow(
                    str(r.get("kind")), str(r.get("counterparty")), str(r.get("dataLabel")),
                    toLong(r.get("cnt"), 0L), toLong(r.get("errorCount"), 0L), toInstant(r.get("lastTs"))));
        }
        return out;
    }

    @Override
    public List<CounterpartyStat> counterpartyStats(String kind, Instant from, Instant to) {
        List<Map<String, Object>> rows = repository.counterpartyStats(blank(kind), from, to);
        List<CounterpartyStat> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            long count = toLong(r.get("cnt"), 0L);
            long errors = toLong(r.get("errorCount"), 0L);
            out.add(new CounterpartyStat(
                    str(r.get("kind")), str(r.get("counterparty")), count, errors,
                    count == 0 ? 0.0 : (double) errors / count, toDouble(r.get("p95"))));
        }
        return out;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long toLong(Object o) {
        return toLong(o, null);
    }

    private static Long toLong(Object o, Long fallback) {
        if (o == null) {
            return fallback;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double toDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof java.sql.Timestamp t) {
            return t.toInstant();
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (o instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        return Instant.parse(String.valueOf(o));
    }
}
