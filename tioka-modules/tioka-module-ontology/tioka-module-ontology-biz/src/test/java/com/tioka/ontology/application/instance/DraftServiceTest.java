package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 草稿服务离线测试（ONT-02，M3-03 §7）：save 乐观锁（revision 冲突报错）、
 * get / getByStoreKey 往返、propose 端口弱依赖（未装配报错）。
 */
class DraftServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private OntDraftRepository repo;
    private DraftService service;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    @BeforeEach
    void setUp() {
        repo = mock(OntDraftRepository.class);
        service = new DraftService(repo, provider((DraftProposer) null), M);
    }

    private static ModelDraft draft(int revision) {
        try {
            return new ModelDraft("lease:S001:HT-001", "lease.contract", 2, "lease:S001",
                    List.of("doc-1"), M.readTree("{\"amount\": {\"value\": 10000, \"status\": \"explicit\"}}"),
                    null, "run-1", null, revision);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 首次保存写revision1() {
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.empty());
        when(repo.save(any(OntDraftDO.class), anyInt())).thenReturn(1);
        int revision = service.save(draft(0));
        assertEquals(1, revision);
        ArgumentCaptor<OntDraftDO> captor = ArgumentCaptor.forClass(OntDraftDO.class);
        verify(repo).save(captor.capture(), eq(0));
        assertEquals("lease.contract", captor.getValue().getDefKey());
        assertEquals("lease:S001", captor.getValue().getStoreKey());
        assertTrue(captor.getValue().getModelJson().contains("10000"));
    }

    @Test
    void 更新按已有revision乐观锁() {
        OntDraftDO existing = new OntDraftDO();
        existing.setInstanceKey("lease:S001:HT-001");
        existing.setRevision(3);
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.of(existing));
        when(repo.save(any(OntDraftDO.class), eq(3))).thenReturn(4);
        assertEquals(4, service.save(draft(99)));
        verify(repo).save(any(OntDraftDO.class), eq(3));
    }

    @Test
    void 乐观锁冲突抛异常() {
        OntDraftDO existing = new OntDraftDO();
        existing.setInstanceKey("lease:S001:HT-001");
        existing.setRevision(5);
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.of(existing));
        when(repo.save(any(OntDraftDO.class), eq(5))).thenReturn(null);
        DraftService.DraftConcurrencyException e = assertThrows(
                DraftService.DraftConcurrencyException.class, () -> service.save(draft(1)));
        assertTrue(e.getMessage().contains("expectedRevision=5"));
    }

    @Test
    void get往返解析JSON() {
        OntDraftDO row = new OntDraftDO();
        row.setInstanceKey("lease:S001:HT-001");
        row.setDefKey("lease.contract");
        row.setDefVersion(2);
        row.setStoreKey("lease:S001");
        row.setDocIds(List.of("doc-1"));
        row.setModelJson("{\"amount\": {\"value\": 10000, \"status\": \"explicit\"}}");
        row.setRevision(2);
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.of(row));
        ModelDraft got = service.get("lease:S001:HT-001").orElseThrow();
        assertEquals("lease.contract", got.defKey());
        assertEquals(2, got.revision());
        assertEquals(10000L, got.modelJson().get("amount").get("value").asLong());
        assertEquals(List.of("doc-1"), got.docIds());
        assertTrue(got.validationJson().isNull());
    }

    @Test
    void propose未装配抛异常() {
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.propose("lease:S001:HT-001"));
        OntDraftDO row = new OntDraftDO();
        row.setInstanceKey("lease:S001:HT-001");
        row.setModelJson("{}");
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.of(row));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.propose("lease:S001:HT-001"));
        assertTrue(e.getMessage().contains("DraftProposer"));
        verify(repo, never()).updateBizDraftRef(any(), any());
    }

    @Test
    void propose成功写回bizDraftRef() {
        OntDraftDO row = new OntDraftDO();
        row.setInstanceKey("lease:S001:HT-001");
        row.setModelJson("{}");
        when(repo.find("lease:S001:HT-001")).thenReturn(Optional.of(row));
        DraftProposer proposer = d -> "biz-ref-1";
        DraftService withProposer = new DraftService(repo, provider(proposer), M);
        assertEquals("biz-ref-1", withProposer.propose("lease:S001:HT-001"));
        verify(repo).updateBizDraftRef("lease:S001:HT-001", "biz-ref-1");
    }

    @Test
    void getByStoreKey列表() {
        OntDraftDO row = new OntDraftDO();
        row.setInstanceKey("lease:S001:HT-002");
        row.setModelJson("{}");
        when(repo.listByStoreKey("lease:S001")).thenReturn(List.of(row));
        List<ModelDraft> drafts = service.getByStoreKey("lease:S001");
        assertEquals(1, drafts.size());
        assertEquals("lease:S001:HT-002", drafts.get(0).instanceKey());
        // Map 引用避免未使用告警
        assertTrue(Map.of().isEmpty());
    }
}
