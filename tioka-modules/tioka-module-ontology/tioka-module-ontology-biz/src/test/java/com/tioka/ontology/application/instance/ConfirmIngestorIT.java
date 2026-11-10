package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.json.CanonicalJson;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.ontology.api.def.PathCatalogEntry;
import com.tioka.ontology.api.def.PublishedDefinition;
import com.tioka.ontology.api.schema.SchemaError;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.schema.SchemaRegistry;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.OntValueIndexRepository;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认投影离线 IT（ONT-02，M3-03 §7 / ADR-029 第 50 条）：
 * 幂等 ACK、哈希不一致拒收（ONT_HASH_MISMATCH，重试耗尽进死信）、Schema 校验失败拒收
 * （ONT_SCHEMA_INVALID）、拉取失败拒收；确认全流程（CONFIRMED 写入 + diff_prev/diff_draft +
 * supersede + ont_value_index 重建 + outbox instance_confirmed）。
 */
class ConfirmIngestorIT {

    private static final ObjectMapper M = new ObjectMapper();

    private static final String INSTANCE_KEY = "lease:S001:HT-001";

    /** 业务系统确认的完整模型（经连接器拉取）。 */
    private static final String CONFIRMED_MODEL = """
            {"feeItems":[{"x-tioka-identity":"RENT@S1",
              "sched":{"baseAmount":{"value":10000,"status":"explicit",
                                     "sources":[{"unitId":"u1","quote":"租金 10000"}]}},
              "abate":{"freeMonths":{"value":2,"status":"explicit"}}}],
             "statements":[{"type":"OBLIGATION","action":{"value":"repair","status":"explicit"}}]}
            """;

    /** 草稿模型（freeMonths 仍为空白 → 与确认模型有差异）。 */
    private static final String DRAFT_MODEL = """
            {"feeItems":[{"x-tioka-identity":"RENT@S1",
              "sched":{"baseAmount":{"value":10000,"status":"explicit"}},
              "abate":{"freeMonths":{"value":0,"status":"blank"}}}]}
            """;

    /** 上一确认版模型（baseAmount 9000 → 与确认模型有差异）。 */
    private static final String PREV_MODEL = """
            {"feeItems":[{"x-tioka-identity":"RENT@S1",
              "sched":{"baseAmount":{"value":9000,"status":"explicit"}}}]}
            """;

    private OntInstanceVersionRepository versions;
    private OntDraftRepository drafts;
    private OntValueIndexRepository valueIndex;
    private EventPublisher publisher;
    private ConfirmIngestor ingestor;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public T getObject() { throw new IllegalStateException("no bean"); }
            @Override public T getObject(Object... args) { throw new IllegalStateException("no bean"); }
            @Override public T getIfAvailable() { return null; }
            @Override public T getIfUnique() { return null; }
        };
    }

    private void setUp(ConfirmedModelClient client, SchemaRegistry registry, DefinitionService definitions) {
        versions = mock(OntInstanceVersionRepository.class);
        drafts = mock(OntDraftRepository.class);
        valueIndex = mock(OntValueIndexRepository.class);
        publisher = mock(EventPublisher.class);
        ingestor = new ConfirmIngestor(M, versions, drafts, valueIndex,
                provider(client), registry == null ? emptyProvider() : provider(registry),
                definitions == null ? emptyProvider() : provider(definitions),
                publisher);
    }

    private static EventEnvelope envelope(Map<String, Object> payload) {
        return new EventEnvelope("evt-1", "contract.model.confirmed", 1, null, null, "biz",
                null, null, null, null, "contract:HT-001", null, 0, null, payload);
    }


    private static JsonNode parsedConfirmedModel() {
        try {
            return M.readTree(CONFIRMED_MODEL);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> payload(String contentHash) {
        return Map.of(
                "contractKey", INSTANCE_KEY,
                "version", 1,
                "contentHash", contentHash,
                "confirmedBy", "biz-user-1",
                "aclPrincipals", List.of("store:S001"));
    }

    private static String correctHash() throws Exception {
        return CanonicalJson.sha256Hex(M, M.readTree(CONFIRMED_MODEL));
    }

    private static OntDraftDO draftRow() {
        OntDraftDO row = new OntDraftDO();
        row.setInstanceKey(INSTANCE_KEY);
        row.setDefKey("lease.contract");
        row.setDefVersion(2);
        row.setStoreKey("lease:S001");
        row.setModelJson(DRAFT_MODEL);
        row.setRevision(1);
        return row;
    }

    private static OntInstanceVersionDO prevRow() {
        OntInstanceVersionDO row = new OntInstanceVersionDO();
        row.setInstanceKey(INSTANCE_KEY);
        row.setVersion(0);
        row.setStatus("SUPERSEDED");
        row.setDefKey("lease.contract");
        row.setStoreKey("lease:S001");
        row.setModelJson(PREV_MODEL);
        return row;
    }

    private void stubCommon(SchemaRegistry registry, DefinitionService definitions) {
        setUp((k, v) -> parsedConfirmedModel(), registry, definitions);
        when(versions.find(eq(INSTANCE_KEY), anyInt())).thenReturn(Optional.empty());
        when(drafts.find(INSTANCE_KEY)).thenReturn(Optional.of(draftRow()));
        when(versions.findLatestConfirmed(INSTANCE_KEY)).thenReturn(Optional.of(prevRow()));
    }

    @Test
    void 重复投递幂等ACK() {
        setUp((k, v) -> parsedConfirmedModel(), null, null);
        when(versions.find(INSTANCE_KEY, 1)).thenReturn(Optional.of(new OntInstanceVersionDO()));
        ingestor.dispatch(envelope(payload("deadbeef")), payload("deadbeef"));
        verify(versions, never()).insert(any());
        verify(valueIndex, never()).rebuild(anyString(), anyInt(), any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void 哈希不一致拒收() {
        stubCommon(null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ingestor.dispatch(envelope(payload("deadbeef")), payload("deadbeef")));
        assertTrue(e.getMessage().startsWith("ONT_HASH_MISMATCH"), e.getMessage());
        verify(versions, never()).insert(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void schema校验失败拒收() {
        SchemaRegistry registry = mock(SchemaRegistry.class);
        stubCommon(registry, null);
        when(versions.findLatestConfirmed(INSTANCE_KEY)).thenReturn(Optional.empty());
        when(registry.get("lease.contract.feeItems@2")).thenReturn(Optional.of(new SchemaDefStub().def()));
        when(registry.validate(eq("lease.contract.feeItems@2"), any()))
                .thenReturn(List.of(new SchemaError("/sched", "SCHEMA_VIOLATION", "missing required amount")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ingestor.dispatch(envelope(payload(correctHash())), payload(correctHash())));
        assertTrue(e.getMessage().startsWith("ONT_SCHEMA_INVALID"), e.getMessage());
        verify(versions, never()).insert(any());
        verify(publisher, never()).publish(any());
    }

    /** SchemaDef 最小桩（哈希值不参与消费链路）。 */
    private static final class SchemaDefStub {
        com.tioka.ontology.api.schema.SchemaDef def() {
            return new com.tioka.ontology.api.schema.SchemaDef(
                    "lease.contract.feeItems", 2, null, "core", "CONFIDENTIAL", "hash", "PUBLISHED");
        }
    }

    @Test
    void 拉取失败拒收() {
        setUp((k, v) -> { throw new RuntimeException("biz down"); }, null, null);
        when(versions.find(eq(INSTANCE_KEY), anyInt())).thenReturn(Optional.empty());
        when(drafts.find(INSTANCE_KEY)).thenReturn(Optional.empty());
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ingestor.dispatch(envelope(payload("deadbeef")), payload("deadbeef")));
        assertTrue(e.getMessage().startsWith("ONT_MODEL_FETCH_FAILED"), e.getMessage());
    }

    @Test
    void payload缺必填字段拒收() {
        setUp((k, v) -> parsedConfirmedModel(), null, null);
        assertThrows(IllegalArgumentException.class,
                () -> ingestor.dispatch(envelope(Map.of("contractKey", INSTANCE_KEY)),
                        Map.of("contractKey", INSTANCE_KEY)));
    }

    @Test
    void 确认全流程() throws Exception {
        // DefinitionService 桩：路径目录带 topics（值索引桥接）
        DefinitionService definitions = mock(DefinitionService.class);
        when(definitions.getDraft("lease.contract")).thenReturn(Optional.of(new PublishedDefinition(
                "lease.contract", 2, null, null, null,
                List.of(new PathCatalogEntry("m.feeItems.sched.baseAmount", null, null, "CNY", List.of("rent")),
                        new PathCatalogEntry("m.statements.action", null, null, null, List.of("obligation"))),
                null, null)));
        stubCommon(null, definitions);

        String hash = CanonicalJson.sha256Hex(M, M.readTree(CONFIRMED_MODEL));
        ingestor.dispatch(envelope(payload(hash)), payload(hash));

        // 新版本 CONFIRMED 写入：diff_prev / diff_draft / 哈希 / 归属
        ArgumentCaptor<OntInstanceVersionDO> rowCaptor = ArgumentCaptor.forClass(OntInstanceVersionDO.class);
        verify(versions).insert(rowCaptor.capture());
        OntInstanceVersionDO row = rowCaptor.getValue();
        assertEquals(INSTANCE_KEY, row.getInstanceKey());
        assertEquals(1, row.getVersion());
        assertEquals("CONFIRMED", row.getStatus());
        assertEquals("lease.contract", row.getDefKey());
        assertEquals(2, row.getDefVersion());
        assertEquals("lease:S001", row.getStoreKey());
        assertEquals(hash, row.getContentHash());
        assertEquals("biz-user-1", row.getConfirmedBy());
        assertEquals(List.of("store:S001"), row.getAclPrincipals());
        assertNotNull(row.getDiffPrev(), "与上一确认版有差异");
        assertTrue(row.getDiffPrev().contains("m.feeItems[RENT@S1].sched.baseAmount"));
        assertNotNull(row.getDiffDraft(), "与草稿有差异（人工更正）");
        assertTrue(row.getDiffDraft().contains("m.feeItems[RENT@S1].abate.freeMonths"));

        // 旧版本被标 SUPERSEDED
        verify(versions).supersedeOthers(INSTANCE_KEY, 1);

        // ont_value_index 重建：路径 / 状态 / topics 桥接
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OntValueIndexDO>> indexCaptor = ArgumentCaptor.forClass(List.class);
        verify(valueIndex).rebuild(eq(INSTANCE_KEY), eq(1), indexCaptor.capture());
        List<OntValueIndexDO> indexRows = indexCaptor.getValue();
        OntValueIndexDO amount = indexRows.stream()
                .filter(r -> r.getPath().equals("m.feeItems[RENT@S1].sched.baseAmount"))
                .findFirst().orElseThrow();
        assertEquals("KNOWN", amount.getStatus());
        assertEquals("10000", amount.getValueText());
        assertEquals(List.of("rent"), amount.getTopics());
        OntValueIndexDO freeMonths = indexRows.stream()
                .filter(r -> r.getPath().equals("m.feeItems[RENT@S1].abate.freeMonths"))
                .findFirst().orElseThrow();
        assertEquals("KNOWN", freeMonths.getStatus());

        // outbox：tioka.ontology.instance_confirmed.v1
        ArgumentCaptor<DomainEvent> eventCaptor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher).publish(eventCaptor.capture());
        DomainEvent event = eventCaptor.getValue();
        assertEquals("tioka.ontology.instance_confirmed.v1", event.event());
        assertEquals(INSTANCE_KEY, event.payload().get("instanceId"));
        assertEquals("1", event.payload().get("versionId"));
        assertEquals(hash, event.payload().get("contentHash"));
        assertEquals("lease.contract", event.payload().get("modelKey"));
    }

    @Test
    void 结构键去选择器() {
        assertEquals("m.feeItems.sched.segments.amount",
                ConfirmIngestor.structuralKeyOf("m.feeItems[RENT@S1].sched.segments[0].amount"));
        assertEquals("m.feeItems", ConfirmIngestor.structuralKeyOf("m.feeItems[*]"));
    }
}
