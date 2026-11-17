package com.tioka.runtime.kernel.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.DataItemOutcome;
import com.tioka.ontology.api.schema.DataItemView;
import com.tioka.runtime.infrastructure.mapper.RuntimeItemMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeItemDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-07 / ADR-066 §1.3：消费方只能经 {@code DataItemReader} 取 data item，
 * 且只有 VALID / VALID_WITH_UNKNOWN 结局交付 payload。
 */
class RuntimeDataItemReaderTest {

    private static final String REF = "lease.finding@1";

    private final StubMapper stub = new StubMapper();
    private final RuntimeDataItemReader reader =
            new RuntimeDataItemReader(new ItemRepository(stub), new ObjectMapper());

    @Test
    void validOutcomeDeliversPayload() {
        stub.item = data("itm-1",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"valid\":true,"
                        + "\"outcome\":\"VALID\",\"reasonCode\":null,\"payload\":{\"findingId\":\"F-1\"}}");

        DataItemView view = reader.read("itm-1", REF).orElseThrow();
        assertEquals("itm-1", view.itemId());
        assertEquals(REF, view.schemaRef());
        assertEquals(DataItemOutcome.VALID, view.outcome());
        assertNull(view.reasonCode());
        assertEquals("F-1", view.payload().get("findingId").asText());
    }

    @Test
    void validWithUnknownDeliversPayload() {
        stub.item = data("itm-2",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"valid\":true,"
                        + "\"outcome\":\"VALID_WITH_UNKNOWN\",\"reasonCode\":null,"
                        + "\"payload\":{\"findingId\":\"F-2\"}}");

        DataItemView view = reader.read("itm-2", null).orElseThrow();
        assertEquals(DataItemOutcome.VALID_WITH_UNKNOWN, view.outcome());
        assertEquals("F-2", view.payload().get("findingId").asText());
    }

    /** 失败与待补录都不交付部分对象：payload 必须为 null（候选存档不可消费）。 */
    @Test
    void failedAndPendingNeverDeliverPayload() {
        stub.item = data("itm-3",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"valid\":false,"
                        + "\"outcome\":\"FAILED\",\"reasonCode\":\"SCHEMA\",\"payload\":null,"
                        + "\"lastCandidate\":{\"findingId\":\"F-3\"}}");
        DataItemView failed = reader.read("itm-3", null).orElseThrow();
        assertEquals(DataItemOutcome.FAILED, failed.outcome());
        assertEquals("SCHEMA", failed.reasonCode());
        assertNull(failed.payload());

        stub.item = data("itm-4",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"valid\":false,"
                        + "\"outcome\":\"HUMAN_PENDING\",\"reasonCode\":\"HUMAN_TIMEOUT\","
                        + "\"payload\":{\"findingId\":\"F-4\"}}");
        DataItemView pending = reader.read("itm-4", null).orElseThrow();
        assertEquals(DataItemOutcome.HUMAN_PENDING, pending.outcome());
        assertNull(pending.payload());
    }

    /** 未知结局按 fail-closed 处理为 FAILED。 */
    @Test
    void unknownOutcomeIsFailClosed() {
        stub.item = data("itm-5",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"outcome\":\"WHATEVER\","
                        + "\"payload\":{\"findingId\":\"F-5\"}}");
        DataItemView view = reader.read("itm-5", null).orElseThrow();
        assertEquals(DataItemOutcome.FAILED, view.outcome());
        assertNull(view.payload());
    }

    /** SCH-07 之前写入的 data item 只有 valid 标记。 */
    @Test
    void legacyItemWithoutOutcomeFallsBackToValidFlag() {
        stub.item = data("itm-6",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"valid\":true,"
                        + "\"payload\":{\"findingId\":\"F-6\"}}");
        DataItemView view = reader.read("itm-6", null).orElseThrow();
        assertEquals(DataItemOutcome.VALID, view.outcome());
        assertEquals("F-6", view.payload().get("findingId").asText());
    }

    @Test
    void schemaRefMismatchAndWrongTypeAndMissingAreEmpty() {
        stub.item = data("itm-7",
                "{\"type\":\"data\",\"schemaRef\":\"" + REF + "\",\"outcome\":\"VALID\",\"payload\":{}}");
        assertFalse(reader.read("itm-7", "lease.finding@2").isPresent());
        assertTrue(reader.read("itm-7", REF).isPresent());
        assertTrue(reader.read("itm-7", null).isPresent());

        stub.item = data("itm-8", "{\"type\":\"message\",\"text\":\"hi\"}");
        stub.item.setType("message");
        assertFalse(reader.read("itm-8", null).isPresent());

        stub.item = null;
        assertFalse(reader.read("itm-9", null).isPresent());
        assertFalse(reader.read(null, null).isPresent());
        assertFalse(reader.read("  ", null).isPresent());
    }

    @Test
    void unparseableContentIsEmpty() {
        stub.item = data("itm-10", "{ not json");
        assertFalse(reader.read("itm-10", null).isPresent());
        stub.item = data("itm-11", null);
        assertFalse(reader.read("itm-11", null).isPresent());
        stub.item = data("itm-12", "[1,2]");
        assertFalse(reader.read("itm-12", null).isPresent());
    }

    private static RuntimeItemDO data(String id, String contentJson) {
        RuntimeItemDO item = new RuntimeItemDO();
        item.setId(id);
        item.setThreadId("th1");
        item.setTurnId("turn-1");
        item.setSeq(1L);
        item.setType("data");
        item.setRole("assistant");
        item.setContentJson(contentJson);
        return item;
    }

    /** 只实现 findById 的桩：其余方法不会被本测试触达。 */
    private static final class StubMapper implements RuntimeItemMapper {
        RuntimeItemDO item;

        @Override
        public RuntimeItemDO findById(String id) {
            return item != null && id.equals(item.getId()) ? item : null;
        }

        @Override
        public Integer assertLease(String turnId, long leaseVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long nextItemSeq(String threadId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(RuntimeItemDO item) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuntimeItemDO> listByThread(String threadId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuntimeItemDO> listAfterSeq(String threadId, long afterSeq, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuntimeItemDO> listByTurn(String turnId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RuntimeItemDO> listByRange(LocalDateTime from, LocalDateTime to) {
            throw new UnsupportedOperationException();
        }
    }
}
