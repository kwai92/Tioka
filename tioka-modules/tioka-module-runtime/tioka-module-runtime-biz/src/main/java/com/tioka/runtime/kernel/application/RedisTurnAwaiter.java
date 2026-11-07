package com.tioka.runtime.kernel.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.kernel.ItemType;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.RuntimeEventType;
import com.tioka.runtime.api.kernel.TurnAwaiter;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.kernel.events.EventStream;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * TurnAwaiter：订阅 thread 的 Redis 事件流，每 5 秒查库兜底（M1-02 RT-08）。
 */
@Service
public class RedisTurnAwaiter implements TurnAwaiter {
    private static final Duration DB_POLL_INTERVAL = Duration.ofSeconds(5);

    private final RuntimeApi runtime;
    private final EventStream streams;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisTurnAwaiter(RuntimeApi runtime,
                            EventStream streams,
                            StringRedisTemplate redis,
                            ObjectMapper mapper) {
        this.runtime = runtime;
        this.streams = streams;
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public RuntimeApi.TurnView await(String turnId, Duration timeout) {
        requireId(turnId, "turnId");
        Duration limit = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        Instant deadline = Instant.now().plus(limit);

        RuntimeApi.TurnView view = runtime.getTurn(turnId);
        if (isAwaitable(view.status())) {
            return view;
        }

        String threadId = view.threadId();
        String lastEntryId = streams.latestEntryId(threadId);
        Instant nextDbPoll = Instant.now().plus(DB_POLL_INTERVAL);

        while (Instant.now().isBefore(deadline)) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                break;
            }

            Duration untilPoll = Duration.between(Instant.now(), nextDbPoll);
            if (untilPoll.isNegative() || untilPoll.isZero()) {
                view = runtime.getTurn(turnId);
                if (isAwaitable(view.status())) {
                    return view;
                }
                nextDbPoll = Instant.now().plus(DB_POLL_INTERVAL);
                untilPoll = DB_POLL_INTERVAL;
            }

            Duration block = remaining.compareTo(untilPoll) < 0 ? remaining : untilPoll;
            if (block.isNegative() || block.isZero()) {
                break;
            }

            List<MapRecord<String, Object, Object>> rows = redis.opsForStream().read(
                    StreamReadOptions.empty().count(32).block(block),
                    StreamOffset.create(streams.streamKey(threadId), ReadOffset.from(lastEntryId)));
            if (rows != null && !rows.isEmpty()) {
                for (MapRecord<String, Object, Object> row : rows) {
                    lastEntryId = row.getId().getValue();
                    if (isWakeEvent(turnId, row)) {
                        view = runtime.getTurn(turnId);
                        if (isAwaitable(view.status())) {
                            return view;
                        }
                    }
                }
            }
        }

        return runtime.getTurn(turnId);
    }

    @Override
    public Optional<String> finalText(String turnId) {
        requireId(turnId, "turnId");
        RuntimeApi.TurnView turn = runtime.getTurn(turnId);
        Optional<String> last = Optional.empty();
        for (RuntimeApi.ItemView item : runtime.listItems(turn.threadId(), 0, 500)) {
            if (!turnId.equals(item.turnId())) {
                continue;
            }
            if (item.type() != ItemType.MESSAGE || !"assistant".equals(item.role())) {
                continue;
            }
            Object text = item.content() == null ? null : item.content().get("text");
            if (text != null) {
                last = Optional.of(String.valueOf(text));
            }
        }
        return last;
    }

    private boolean isWakeEvent(String turnId, MapRecord<String, Object, Object> row) {
        Object typeObj = row.getValue().get("type");
        Object dataObj = row.getValue().get("data");
        if (typeObj == null || dataObj == null) {
            return false;
        }
        String type = String.valueOf(typeObj);
        if (!(RuntimeEventType.TURN_COMPLETED.wire().equals(type)
                || RuntimeEventType.TURN_FAILED.wire().equals(type)
                || RuntimeEventType.TURN_CANCELLED.wire().equals(type)
                || RuntimeEventType.TURN_WAITING.wire().equals(type)
                || RuntimeEventType.APPROVAL_REQUESTED.wire().equals(type))) {
            return false;
        }
        try {
            JsonNode root = mapper.readTree(String.valueOf(dataObj));
            JsonNode tid = root.get("turnId");
            return tid != null && turnId.equals(tid.asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAwaitable(TurnStatus status) {
        return status.isTerminal()
                || status == TurnStatus.WAITING_APPROVAL
                || status == TurnStatus.WAITING_CHILD;
    }

    private static void requireId(String id, String name) {
        if (id == null || id.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, name + " 必填");
        }
    }
}
