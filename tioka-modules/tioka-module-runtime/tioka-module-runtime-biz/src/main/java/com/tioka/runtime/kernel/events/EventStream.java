package com.tioka.runtime.kernel.events;

import com.tioka.runtime.config.KernelProperties;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class EventStream {
    private static final String KEY_PREFIX = "rt:events:";
    private static final String CUR_PREFIX = "rt:cur:";

    private final StringRedisTemplate redis;
    private final KernelProperties props;

    public EventStream(StringRedisTemplate redis, KernelProperties props) {
        this.redis = redis;
        this.props = props;
    }

    public String streamKey(String threadId) {
        return KEY_PREFIX + threadId;
    }

    public String curKey(String threadId) {
        return CUR_PREFIX + threadId;
    }

    /** @return Redis stream entry id */
    public String publish(String threadId, String eventId, String type, String dataJson) {
        String key = streamKey(threadId);
        RecordId id = redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "eid", eventId == null ? "" : eventId,
                "type", type,
                "data", dataJson
        )).withStreamKey(key));
        redis.opsForStream().trim(key, props.getEvents().getStreamMaxlen(), true);
        redis.expire(key, props.getEvents().getStreamTtlRunning());
        return id == null ? null : id.getValue();
    }

    public void setCurrentEntry(String threadId, String entryId) {
        redis.opsForValue().set(curKey(threadId), entryId, props.getEvents().getStreamTtlRunning());
    }

    public void clearCurrentEntry(String threadId) {
        redis.delete(curKey(threadId));
    }

    public String currentEntry(String threadId) {
        return redis.opsForValue().get(curKey(threadId));
    }

    /** 读取单条 stream 事件（用于崩溃回收解析 rt:cur 预占 seq）。 */
    public StreamEvent getEntry(String threadId, String entryId) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }
        List<StreamEvent> rows = range(threadId, entryId, entryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void expireAfterTurn(String threadId) {
        redis.expire(streamKey(threadId), props.getEvents().getStreamTtlAfterTurn());
    }

    public String latestEntryId(String threadId) {
        List<MapRecord<String, Object, Object>> rows = redis.opsForStream().reverseRange(
                streamKey(threadId),
                Range.unbounded(),
                Limit.limit().count(1));
        if (rows == null || rows.isEmpty()) {
            return "0-0";
        }
        return rows.get(0).getId().getValue();
    }

    public List<StreamEvent> range(String threadId, String fromInclusive, String toInclusive) {
        List<MapRecord<String, Object, Object>> rows = redis.opsForStream().range(
                streamKey(threadId),
                Range.closed(fromInclusive, toInclusive));
        return map(rows);
    }

    public List<StreamEvent> rangeExclusiveAfter(String threadId, String afterExclusive, String toInclusive) {
        List<MapRecord<String, Object, Object>> rows = redis.opsForStream().range(
                streamKey(threadId),
                Range.from(Range.Bound.exclusive(afterExclusive)).to(Range.Bound.inclusive(toInclusive)));
        return map(rows);
    }

    private static List<StreamEvent> map(List<MapRecord<String, Object, Object>> rows) {
        List<StreamEvent> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (MapRecord<String, Object, Object> r : rows) {
            Map<Object, Object> v = r.getValue();
            out.add(new StreamEvent(
                    r.getId().getValue(),
                    str(v.get("eid")),
                    str(v.get("type")),
                    str(v.get("data"))));
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    public record StreamEvent(String entryId, String eventId, String type, String dataJson) {}
}
