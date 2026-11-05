package com.tioka.orchestration.application;

import com.tioka.orchestration.infrastructure.OrchRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchAttentionDO;
import com.tioka.orchestration.infrastructure.persistence.OrchIdemDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRunDO;
import com.tioka.orchestration.infrastructure.persistence.OrchWhyDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-03：发布 ignore 规则后同 eventType 不建业务 Run，why 含 ignore。
 */
class OrchRuleIngestTest {
    private FakeOrchRepository repo;
    private OrchRuleService ruleService;
    private OrchService orch;

    @BeforeEach
    void setUp() {
        repo = new FakeOrchRepository();
        ruleService = new OrchRuleService(repo);
        orch = new OrchService(repo, ruleService);
    }

    @Test
    void publishedIgnoreRuleDoesNotCreateBusinessRun() {
        var draft = ruleService.saveDraft("drop", "demo.event", Map.of("type", "ignore"));
        ruleService.publish(draft.id());

        String first = orch.ingestEvent("demo.event", null, Map.of());
        assertEquals("", first);
        assertEquals(0, repo.runs.size());
        assertTrue(repo.whys.stream().anyMatch(w -> "ignore".equals(w.getKind())
                || (w.getDetail() != null && w.getDetail().contains("ignore"))));

        String second = orch.ingestEvent("demo.event", null, Map.of());
        assertEquals("", second);
        assertEquals(0, repo.runs.size());
    }

    @Test
    void noRuleDefaultsToCreateRun() {
        String runId = orch.ingestEvent("unknown.event", null, Map.of());
        assertTrue(runId.startsWith("run_"));
        assertEquals(1, repo.runs.size());
    }

    @Test
    void openAttentionActionCreatesRunAndAttention() {
        var draft = ruleService.saveDraft("attn", "need.human",
                Map.of("type", "open_attention", "title", "请审批"));
        ruleService.publish(draft.id());
        String runId = orch.ingestEvent("need.human", null, Map.of("x", 1));
        assertTrue(runId.startsWith("run_"));
        assertEquals(1, repo.runs.size());
        assertEquals("SUSPENDED", repo.runs.get(0).getStatus());
        assertEquals(1, repo.attentions.size());
    }

    static final class FakeOrchRepository extends OrchRepository {
        final Map<String, OrchRuleDO> rules = new ConcurrentHashMap<>();
        final List<OrchRunDO> runs = new ArrayList<>();
        final List<OrchWhyDO> whys = new ArrayList<>();
        final List<OrchAttentionDO> attentions = new ArrayList<>();
        final AtomicInteger whyOrd = new AtomicInteger();

        FakeOrchRepository() {
            super(null, null, null, null, null);
        }

        @Override public String findRunIdByIdem(String key) { return null; }
        @Override public void insertIdem(OrchIdemDO row) {}
        @Override public void insertRun(OrchRunDO row) { runs.add(row); }
        @Override public void updateRun(OrchRunDO row) {
            for (int i = 0; i < runs.size(); i++) {
                if (runs.get(i).getId().equals(row.getId())) { runs.set(i, row); return; }
            }
        }
        @Override public OrchRunDO findRun(String id) {
            return runs.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
        }
        @Override public void insertAttention(OrchAttentionDO row) { attentions.add(row); }
        @Override public int nextWhyOrd(String runId) { return whyOrd.incrementAndGet(); }
        @Override public void insertWhy(OrchWhyDO row) { whys.add(row); }
        @Override public void insertRule(OrchRuleDO row) { rules.put(row.getId(), row); }
        @Override public void updateRule(OrchRuleDO row) { rules.put(row.getId(), row); }
        @Override public OrchRuleDO findRule(String id) { return rules.get(id); }
        @Override public List<OrchRuleDO> listRules() { return List.copyOf(rules.values()); }
        @Override public OrchRuleDO findLatestPublishedRule(String eventType) {
            return rules.values().stream()
                    .filter(r -> eventType.equals(r.getEventType()) && Boolean.TRUE.equals(r.getPublished()))
                    .max(Comparator.comparingInt(r -> r.getVersion() == null ? 0 : r.getVersion()))
                    .orElse(null);
        }
        @Override public int nextRuleVersion(String eventType) { return 1; }
    }
}
