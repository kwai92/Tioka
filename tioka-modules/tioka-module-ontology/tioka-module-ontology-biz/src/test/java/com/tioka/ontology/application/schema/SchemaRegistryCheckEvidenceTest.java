package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.SchemaError;
import com.tioka.ontology.domain.TextNormalizer;
import com.tioka.ontology.infrastructure.DocumentRepository;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCH-03：SchemaRegistry.checkEvidence 真实现——
 * doc on（绑定生效）/ doc off（跳过）/ UNIT_NOT_VISIBLE / QUOTE_MISMATCH / UNIT_NOT_FOUND。
 */
class SchemaRegistryCheckEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InMemoryDocumentRepository repository;
    private SchemaRegistry registry;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        seed();
        registry = new SchemaRegistry(MAPPER, provider(new EvidenceBinder(repository)));
        registry.publish("lease.finding", 1, schema(), "test");
    }

    private static JsonNode schema() {
        try {
            return MAPPER.readTree("""
                    {
                      "type": "object",
                      "x-tioka-evidence": "required",
                      "additionalProperties": false,
                      "required": ["findingId", "evidence"],
                      "properties": {
                        "findingId": {"type": "string"},
                        "evidence": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "additionalProperties": false,
                            "required": ["unitId", "quote"],
                            "properties": {
                              "unitId": {"type": "string"},
                              "quote": {"type": "string"}
                            }
                          }
                        }
                      }
                    }""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode payload(String unitId, String quote) {
        try {
            return MAPPER.readTree("""
                    {"findingId":"F-1","evidence":[{"unitId":"%s","quote":"%s"}]}"""
                    .formatted(unitId, quote.replace("\"", "\\\"")));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void seed() {
        List<DocumentRepository.UnitRow> units = new ArrayList<>();
        units.add(unit("doc1:1:1", 1, 1, "免租期为三个月。"));
        units.add(unit("doc2:1:1", 1, 1, "押金为两个月租金。"));
        DocumentRepository.DocumentRow doc1 = new DocumentRepository.DocumentRow(
                "doc1", "hash-doc1", "1.0.0", "application/pdf", "doc1.pdf",
                null, null, "k/doc1", null, "ws1");
        doc1.parseStatus = "PENDING";
        repository.persistParsed(doc1,
                List.of(new DocumentRepository.PageRow("doc1", 1, "TEXT", "text", 100.0, null)),
                units.subList(0, 1), List.of());
        DocumentRepository.DocumentRow doc2 = new DocumentRepository.DocumentRow(
                "doc2", "hash-doc2", "1.0.0", "application/pdf", "doc2.pdf",
                null, null, "k/doc2", null, "ws1");
        doc2.parseStatus = "PENDING";
        repository.persistParsed(doc2,
                List.of(new DocumentRepository.PageRow("doc2", 1, "TEXT", "text", 100.0, null)),
                units.subList(1, 2), List.of());
    }

    private DocumentRepository.UnitRow unit(String unitId, int pageNo, int seq, String text) {
        DocumentRepository.UnitRow u = new DocumentRepository.UnitRow();
        u.unitId = unitId;
        u.docId = unitId.split(":")[0];
        u.pageNo = pageNo;
        u.seq = seq;
        u.kind = "PARA";
        u.text = text;
        u.normText = TextNormalizer.normalize(text);
        return u;
    }

    @Test
    void validQuotePasses() {
        List<SchemaError> errors = registry.checkEvidence("lease.finding@1",
                payload("doc1:1:1", "免租期为三个月"), List.of("doc1"));
        assertTrue(errors.isEmpty(), () -> errors.toString());
    }

    @Test
    void changedDigitFailsWithQuoteMismatch() {
        List<SchemaError> errors = registry.checkEvidence("lease.finding@1",
                payload("doc1:1:1", "免租期为四个月"), List.of("doc1"));
        assertEquals(1, errors.size());
        assertEquals("QUOTE_MISMATCH", errors.get(0).code());
        assertEquals("/evidence/0", errors.get(0).pointer());
    }

    @Test
    void unitFromInvisibleDocumentRejected() {
        List<SchemaError> errors = registry.checkEvidence("lease.finding@1",
                payload("doc2:1:1", "押金为两个月租金"), List.of("doc1"));
        assertEquals(1, errors.size());
        assertEquals("UNIT_NOT_VISIBLE", errors.get(0).code());
    }

    @Test
    void unknownUnitRejected() {
        List<SchemaError> errors = registry.checkEvidence("lease.finding@1",
                payload("docX:1:1", "任意引文"), List.of("doc1"));
        assertEquals(1, errors.size());
        assertEquals("UNIT_NOT_FOUND", errors.get(0).code());
    }

    @Test
    void evidenceSkippedWhenDocDisabled() {
        // 双开关降级：doc.enabled=false → 无 EvidenceBinder → 空清单（跳过）
        SchemaRegistry noBinder = new SchemaRegistry(MAPPER);
        noBinder.publish("lease.finding", 1, schema(), "test");
        List<SchemaError> errors = noBinder.checkEvidence("lease.finding@1",
                payload("doc1:1:1", "改一个字也不查"), List.of("doc1"));
        assertTrue(errors.isEmpty());
    }

    @Test
    void schemaMissingStillReported() {
        List<SchemaError> errors = registry.checkEvidence("nope@9",
                payload("doc1:1:1", "x"), List.of("doc1"));
        assertEquals(1, errors.size());
        assertEquals("SCHEMA_NOT_FOUND", errors.get(0).code());
    }

    private static ObjectProvider<EvidenceBinder> provider(EvidenceBinder binder) {
        return new ObjectProvider<>() {
            @Override public EvidenceBinder getObject() { return binder; }
            @Override public EvidenceBinder getObject(Object... args) { return binder; }
            @Override public EvidenceBinder getIfAvailable() { return binder; }
            @Override public EvidenceBinder getIfUnique() { return binder; }
        };
    }
}
