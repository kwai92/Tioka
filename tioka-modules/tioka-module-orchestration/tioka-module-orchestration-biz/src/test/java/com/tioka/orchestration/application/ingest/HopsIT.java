package com.tioka.orchestration.application.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.orchestration.api.engine.OrchApi;
import com.tioka.orchestration.application.decide.OrchDecideService;
import com.tioka.orchestration.infrastructure.OrchEngineRepository;
import com.tioka.orchestration.infrastructure.persistence.OrchEventDO;
import com.tioka.orchestration.infrastructure.persistence.OrchRuleDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ORC-07（M2-00 第 75 行）跳数熔断——离线用例。
 *
 * <p>生产行为（已实现，{@code OrchIngestService#ingest} 55–58 行）：派生事件携带
 * {@code hops}（派生深度），超过配置键 {@code tioka.orch.max-hops}（默认 8）即熔断——
 * 事件落库为 {@code FILTERED}（strength=INFERRED，幂等键追加
 * {@code ":filtered:hops_exceeded"}），不再进入规则决定（{@code OrchDecideService.decide}）。
 *
 * <p>本类纯 JUnit5 + Mockito，不依赖 Spring 上下文与数据库（离线），与模块内
 * {@code PluginHostIT} 同一惯例：命名 {@code *IT} 由 failsafe/CI 承接，
 * 默认 surefire 构建排除，不影响离线验证。
 */
class HopsIT {

    private static final int MAX_HOPS = 8;

    private OrchEngineRepository repo;
    private OrchDecideService decideService;
    private PrincipalHolder principals;
    private OrchIngestService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrchEngineRepository.class);
        decideService = mock(OrchDecideService.class);
        principals = mock(PrincipalHolder.class);
        when(principals.find()).thenReturn(Optional.of(
                new Principal("user-1", "ws-1", PrincipalType.USER, Set.of(), Set.of(), "test")));
        // 正常路径：幂等键从未见过；匹配规则返回空 → 事件 STORED 后转 FILTERED（无规则）
        when(repo.findEventIdByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(repo.findActiveRulesForEventType(anyString())).thenReturn(List.of());
        service = new OrchIngestService(repo, decideService, principals, new ObjectMapper(), MAX_HOPS);
    }

    private OrchApi.EventEnvelope event(int hops) {
        return new OrchApi.EventEnvelope(
                "orch", "orch.derived", "src-1:evt-" + hops, "subj-1",
                Map.of("tioka.addressed", "false"), Map.of("n", hops), hops);
    }

    /** 超过上限（hops = max + 1）→ 熔断：FILTERED 落库，不走规则决定。 */
    @Test
    void hopsBeyondLimitIsFiltered() {
        OrchApi.EventAck ack = service.ingest(event(MAX_HOPS + 1));

        assertEquals(OrchApi.Disposition.FILTERED, ack.disposition());
        ArgumentCaptor<OrchEventDO> row = ArgumentCaptor.forClass(OrchEventDO.class);
        verify(repo).insertEvent(row.capture());
        OrchEventDO stored = row.getValue();
        assertEquals(OrchApi.Disposition.FILTERED.name(), stored.getDisposition());
        assertEquals(OrchApi.Strength.INFERRED.name(), stored.getStrength());
        assertEquals(MAX_HOPS + 1, stored.getHops());
        assertTrue(stored.getIdempotencyKey().endsWith(":filtered:hops_exceeded"),
                "熔断落库幂等键应带 hops_exceeded 原因: " + stored.getIdempotencyKey());
        // 熔断即终止：不进入规则决定
        verify(decideService, never()).decide(anyString(), any(), any(), any(), anyString());
    }

    /** 恰好等于上限（hops = max）不熔断，正常入库（无匹配规则 → FILTERED 且原因非熔断）。 */
    @Test
    void hopsAtLimitIsAccepted() {
        OrchApi.EventAck ack = service.ingest(event(MAX_HOPS));

        // 无匹配规则 → 仍 FILTERED，但不带 hops_exceeded 幂等键（走正常无规则过滤路径）
        assertEquals(OrchApi.Disposition.FILTERED, ack.disposition());
        ArgumentCaptor<OrchEventDO> row = ArgumentCaptor.forClass(OrchEventDO.class);
        verify(repo, times(1)).insertEvent(row.capture());
        assertEquals(OrchApi.Disposition.STORED.name(), row.getValue().getDisposition(),
                "hops 未超限时先正常落库为 STORED，再由无规则转 FILTERED");
        assertEquals(MAX_HOPS, row.getValue().getHops());
        assertTrue(!row.getValue().getIdempotencyKey().contains("hops_exceeded"),
                "非熔断路径幂等键不得追加 hops_exceeded");
        verify(decideService, never()).decide(anyString(), any(), any(), any(), anyString());
    }

    /** 多跳派生链：hops 0..8 逐跳投递均被接受，第 9 跳熔断——模拟派生循环。 */
    @Test
    void derivedChainBreaksAtConfiguredDepth() {
        for (int hops = 0; hops <= MAX_HOPS; hops++) {
            OrchApi.EventAck ack = service.ingest(event(hops));
            assertEquals(OrchApi.Disposition.FILTERED, ack.disposition(),
                    "无规则场景 hops=" + hops + " 亦为 FILTERED，但不得因熔断落库");
        }
        // 前面全部走了正常路径（STORED 落库 + 无规则转 FILTERED）
        verify(repo, times(MAX_HOPS + 1)).insertEvent(any());
        // 第 9 跳熔断：FILTERED 直落（幂等键带原因），且不再调用 decide
        OrchApi.EventAck overLimit = service.ingest(event(MAX_HOPS + 1));
        assertEquals(OrchApi.Disposition.FILTERED, overLimit.disposition());
        ArgumentCaptor<OrchEventDO> row = ArgumentCaptor.forClass(OrchEventDO.class);
        verify(repo, times(MAX_HOPS + 2)).insertEvent(row.capture());
        OrchEventDO last = row.getValue();
        assertEquals(OrchApi.Disposition.FILTERED.name(), last.getDisposition());
        assertEquals(MAX_HOPS + 1, last.getHops());
        assertTrue(last.getIdempotencyKey().endsWith(":filtered:hops_exceeded"));
    }

    /** 有匹配规则时，未超限的派生事件正常进入规则决定；超限的同键事件熔断。 */
    @Test
    void derivedEventWithinLimitStillReachesRules() {
        OrchRuleDO rule = new OrchRuleDO();
        rule.setId("rule-1");
        when(repo.findActiveRulesForEventType("orch.derived")).thenReturn(List.of(rule));
        when(repo.findEventIdByIdempotencyKey(anyString())).thenReturn(Optional.empty());

        OrchApi.EventAck ok = service.ingest(event(1));
        assertEquals(OrchApi.Disposition.STORED, ok.disposition());
        verify(decideService, times(1)).decide(anyString(), any(), eq(rule), any(), eq("ws-1"));

        OrchApi.EventAck overLimit = service.ingest(event(MAX_HOPS + 1));
        assertEquals(OrchApi.Disposition.FILTERED, overLimit.disposition());
        // decide 仍只有第一次那次
        verify(decideService, times(1)).decide(anyString(), any(), eq(rule), any(), anyString());
    }
}
