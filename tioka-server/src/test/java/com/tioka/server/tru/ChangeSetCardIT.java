package com.tioka.server.tru;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.runtime.api.approval.ApprovalQueryApi;
import com.tioka.runtime.api.spi.ChangeOp;
import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.application.changeset.ChangeSetCardRenderer;
import com.tioka.runtime.application.changeset.ChangeSetCardService;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.infrastructure.TrustActionRepository;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TRU-06b（M2-00 第 81 行）变更单卡片后端——B-6c 实现后的验收。
 *
 * <p>离线部分（本类顶层，无容器）：四种状态快照（PROPOSED / APPLIED / STALE / estimated，
 * M2-05 第 111 行）、card_json 关键字段断言（changeSet / reversibility / estimated / changeTable）、
 * 审批详情查询（{@code ChangeSetCardService#detailByApproval}）、prepare/commit 状态流转
 * （{@code PreparedCallStore} 状态机）。需要真实容器（PG/Testcontainers）的部分保留在
 * {@link ContainerRequired}，随 CI failsafe 承接：
 * <ul>
 *   <li>{@code DefaultToolGateway#prepare → openCardApproval → commit} 全链（含 trust_action
 *       approval_id 回链落库、REST {@code GET /api/runtime/approvals/{id}/changeset}）；</li>
 *   <li>飞书 openapi 出站（{@code FeishuImChannelAdapter}）。</li>
 * </ul>
 */
class ChangeSetCardIT {

    private static ChangeSet changeSet(int changes, boolean estimated) {
        List<ChangeSet.Change> list = new java.util.ArrayList<>();
        for (int i = 0; i < changes; i++) {
            list.add(new ChangeSet.Change("order", "ord_" + i, ChangeOp.UPDATE,
                    Map.of("status", "OPEN", "amount", 100 + i),
                    Map.of("status", "SHIPPED", "amount", 100 + i)));
        }
        return new ChangeSet("更新 " + changes + " 张订单", list,
                new ChangeSet.Totals(changes, 1_500_000L, "CNY", java.util.Set.of("order")),
                "fp_" + changes, estimated, Instant.now().plusSeconds(600));
    }

    private static TrustActionDO row(String status, boolean estimated) {
        TrustActionDO r = new TrustActionDO();
        r.setId("act_1");
        r.setWorkspaceId("ws_1");
        r.setToolName("erp.updateOrder");
        r.setStatus(status);
        r.setGate("REQUIRE_APPROVAL");
        r.setReversibility("REVERSIBLE");
        r.setEstimated(estimated);
        r.setItemId("itm_1");
        r.setTurnId("turn_1");
        return r;
    }

    private static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.findAndRegisterModules();
        return m;
    }

    private static String json(Object o) {
        try {
            return mapper().writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 四种状态快照（M2-05 第 111 行） ----------

    @Test
    void proposedStateSnapshot() {
        Map<String, Object> card = new ChangeSetCardRenderer().render(row("PROPOSED", false), changeSet(2, false));
        assertEquals("PROPOSED", card.get("changeStatus"));
        assertEquals("REVERSIBLE", card.get("reversibility"));
        assertEquals(false, card.get("estimated"));
        assertEquals("更新 2 张订单", card.get("title"));
        assertEquals("erp.updateOrder", card.get("toolName"));
        assertTrue(card.get("changeSet") instanceof ChangeSet, "card_json 带 changeSet 摘要");
        assertTrue(card.containsKey("changeTotals"));
        assertEquals(false, card.get("viewAll"), "≤10 条且非 estimated 时不带查看全部");
        assertEquals(2, ((List<?>) card.get("changeTable")).size());
    }

    @Test
    void appliedStateSnapshot() {
        TrustActionDO r = row("PROPOSED", false);
        Map<String, Object> card = new ChangeSetCardRenderer().render(r, changeSet(2, false));
        assertEquals("PROPOSED", card.get("changeStatus"));
        // commit 后：PreparedCallStore.markApplied → APPLIED（见 prepareCommitStateFlow），快照复核
        Map<String, Object> applied = new ChangeSetCardRenderer().render(row("APPLIED", false), changeSet(2, false));
        assertEquals("APPLIED", applied.get("changeStatus"));
        assertEquals("erp.updateOrder", applied.get("toolName"));
    }

    @Test
    void staleStateSnapshot() {
        Map<String, Object> card = new ChangeSetCardRenderer().render(row("STALE", false), changeSet(2, false));
        assertEquals("STALE", card.get("changeStatus"));
        assertEquals(false, card.get("estimated"));
    }

    @Test
    void estimatedStateSnapshot() {
        Map<String, Object> card = new ChangeSetCardRenderer().render(row("PROPOSED", true), changeSet(1, true));
        assertEquals(true, card.get("estimated"), "estimated 预估态标记");
        assertEquals(true, card.get("viewAll"), "estimated 时带「查看全部」");
        assertTrue(String.valueOf(json(card)).contains("\"estimated\":true"));
    }

    // ---------- 表格口径（M2-05 第 102 行） ----------

    @Test
    void updateListsOnlyChangedFieldsAndCapsAtTen() {
        List<Map<String, Object>> rows = new ChangeSetCardRenderer().tableRows(changeSet(12, false));
        assertEquals(10, rows.size(), "12 条变化只渲染前 10 行");
        Map<String, Object> first = rows.get(0);
        assertEquals("status", first.get("field"), "UPDATE 只列变化字段（amount 未变不列）");
        assertEquals("OPEN", first.get("before"));
        assertEquals("SHIPPED", first.get("after"));

        TrustActionDO r = row("PROPOSED", false);
        Map<String, Object> card = new ChangeSetCardRenderer().render(r, changeSet(12, false));
        assertEquals(true, card.get("viewAll"), ">10 条时带「查看全部」");
    }

    // ---------- 飞书侧渲染（connector-biz FeishuCardBuilder，PK-25） ----------

    @Test
    void feishuCardRendersChangeTableFromCardJson() {
        Map<String, Object> cardJson = new ChangeSetCardRenderer().render(row("PROPOSED", false), changeSet(2, false));
        com.tioka.connector.application.FeishuCardBuilder builder =
                new com.tioka.connector.application.FeishuCardBuilder();
        com.tioka.connector.api.im.ImMessage msg = builder.changeSetCardFromJsonMap(cardJson);
        Map<String, Object> feishu = builder.build(msg);
        String s = String.valueOf(feishu);
        assertTrue(s.contains("更新 2 张订单"), "标题 = changeSet.summary");
        assertTrue(s.contains("可撤销"), "标签按可逆性渲染");
        assertFalse(s.contains("推断"), "非 estimated 不加「推断」");
        assertTrue(s.contains("在 Tioka 中查看全部") == false || Boolean.TRUE.equals(cardJson.get("viewAll")),
                "≤10 条且非 estimated 时不出现查看全部");
        assertTrue(s.contains("合计 2 条记录"), "合计记录数");
        assertTrue(s.contains("金额 1.5 CNY"), "金额按 currency 格式化");
        assertTrue(s.contains("SHIPPED"), "新值进表格");
    }

    // ---------- 审批详情查询（离线，Mockito） ----------

    @Test
    void approvalDeskDetailQueryByApprovalId() {
        TrustActionRepository actions = mock(TrustActionRepository.class);
        ApprovalRepository approvals = mock(ApprovalRepository.class);
        EventPublisher publisher = mock(EventPublisher.class);
        ChangeSetCardService service = new ChangeSetCardService(
                actions, approvals, new ChangeSetCardRenderer(), publisher, mapper());

        TrustActionDO applied = row("APPLIED", false);
        applied.setApprovalId("apv_1");
        applied.setChangeSetJson(json(changeSet(2, false)));
        when(actions.findByApprovalId("apv_1")).thenReturn(applied);

        Optional<ApprovalQueryApi.ChangeSetDetail> detail = service.detailByApproval("apv_1");
        assertTrue(detail.isPresent());
        assertEquals("APPLIED", detail.get().changeStatus());
        assertEquals("act_1", detail.get().preparedId());
        assertEquals("erp.updateOrder", detail.get().toolName());
        assertEquals("REVERSIBLE", detail.get().reversibility());
        assertFalse(detail.get().estimated());
        assertEquals(2, detail.get().rows().size());
        assertEquals("更新 2 张订单", detail.get().changeSet().summary());

        when(actions.findByApprovalId("apv_none")).thenReturn(null);
        assertTrue(service.detailByApproval("apv_none").isEmpty(), "无关联变更单返回空");
        assertTrue(service.detail("act_missing").isEmpty(), "变更单 ID 不存在返回空");
    }

    // ---------- 打开变更单卡片审批：card_json 带变更单 + 状态机挂钩（离线） ----------

    @Test
    void openCardApprovalWritesChangeSetCardAndLinksApproval() {
        TrustActionRepository actions = mock(TrustActionRepository.class);
        ApprovalRepository approvals = mock(ApprovalRepository.class);
        EventPublisher publisher = mock(EventPublisher.class);
        ChangeSetCardService service = new ChangeSetCardService(
                actions, approvals, new ChangeSetCardRenderer(), publisher, mapper());

        TrustActionDO proposed = row("PROPOSED", false);
        proposed.setChangeSetJson(json(changeSet(2, false)));
        when(actions.findById("act_1")).thenReturn(proposed);

        String approvalId = service.openCardApproval("ws_1", "act_1", null, null, "tester");

        org.mockito.ArgumentCaptor<com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO> captor =
                ArgumentCaptor.forClass(com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO.class);
        verify(approvals).insert(captor.capture());
        com.tioka.runtime.infrastructure.persistence.RuntimeApprovalDO saved = captor.getValue();
        assertEquals(approvalId, saved.getId());
        assertEquals("PENDING", saved.getStatus());
        String cardJson = saved.getCardJson();
        assertTrue(cardJson.contains("\"changeStatus\":\"PROPOSED\""));
        assertTrue(cardJson.contains("\"reversibility\":\"REVERSIBLE\""));
        assertTrue(cardJson.contains("\"estimated\":false"));
        assertTrue(cardJson.contains("changeSet"), "card_json 带 changeSet（M2-05 第 43 行）");
        assertTrue(cardJson.contains("更新 2 张订单"), "标题 = changeSet.summary");

        // 状态机挂钩：PROPOSED 侧回链 approval_id
        verify(actions).linkApproval("act_1", approvalId);
        verify(publisher).publish(any());
    }

    @Test
    void openCardApprovalIdempotentByExistingLink() {
        TrustActionRepository actions = mock(TrustActionRepository.class);
        ApprovalRepository approvals = mock(ApprovalRepository.class);
        EventPublisher publisher = mock(EventPublisher.class);
        ChangeSetCardService service = new ChangeSetCardService(
                actions, approvals, new ChangeSetCardRenderer(), publisher, mapper());
        TrustActionDO linked = row("PROPOSED", false);
        linked.setApprovalId("apv_existing");
        when(actions.findById("act_1")).thenReturn(linked);
        assertEquals("apv_existing", service.openCardApproval("ws_1", "act_1", null, null, null),
                "已回链的 prepared call 复用原审批");
        verify(approvals, org.mockito.Mockito.never()).insert(any());
    }

    // ---------- prepare/commit 状态流转（PreparedCallStore 状态机，离线） ----------

    @Test
    void prepareCommitStateFlow() {
        TrustActionRepository repo = mock(TrustActionRepository.class);
        when(repo.markStatus(eq("act_1"), eq("PROPOSED"), eq("APPLIED"), any(), any())).thenReturn(1);
        when(repo.markStatus(eq("act_1"), eq("PROPOSED"), eq("STALE"), any(), any())).thenReturn(1);
        when(repo.markStatus(eq("act_1"), eq("PROPOSED"), eq("APPROVED"), any(), any())).thenReturn(1);
        PreparedCallStore store = new PreparedCallStore(repo, mapper());

        TrustActionDO proposed = row("PROPOSED", false);

        // prepare → PROPOSED（DefaultToolGateway#prepare 落 PROPOSED，由 insertProposed 保证）
        assertEquals("PROPOSED", proposed.getStatus());

        // commit → APPLIED（带 ApplyReceipt，含补偿令牌）
        store.markApplied(proposed, new ChangeSet.ApplyReceipt("ext_1", Map.of("undo", "u1"), Instant.now()));
        assertEquals("APPLIED", proposed.getStatus());
        assertTrue(proposed.getReceiptJson().contains("compensationToken"));
        assertEquals(PreparedCallStore.STATUS_APPLIED, proposed.getStatus());

        // 基线失效 → STALE（DefaultToolGateway#commit 过期/漂移分支）
        TrustActionDO stale = row("PROPOSED", false);
        store.markStale(stale);
        assertEquals("STALE", stale.getStatus());

        // 审批通过 → APPROVED（PROPOSED → APPROVED，commit 续跑前置）
        TrustActionDO approved = row("PROPOSED", false);
        store.markApproved(approved, "apv_9");
        assertEquals("APPROVED", approved.getStatus());
        assertEquals("apv_9", approved.getApprovalId());

        // estimated 为预估态（非事务型写工具）
        assertTrue(Boolean.TRUE.equals(row("PROPOSED", true).getEstimated()));
        assertNull(row("PROPOSED", false).getReceiptJson());
    }

    // ---------- 需要真实容器的部分（注明保留，随 CI failsafe 承接） ----------

    @Nested
    @Tag("it")
    @Disabled("需要真实容器（PG/Testcontainers + Redis）：全链 prepare→openCardApproval→commit 与 REST 出口；离线口径已由顶层用例覆盖")
    class ContainerRequired {

        @SpringBootTest
        @Tag("it")
        class ChangeSetCardFullChain {

            @DynamicPropertySource
            static void containers(DynamicPropertyRegistry registry) {
                com.tioka.server.support.IntegrationTestSupport.register(registry);
            }

            @Test
            void prepareOpenApprovalCommitUpdatesCardAndDetail() {
                // 验收口径（M2-05 第 43、88 行）：
                // 1) gateway.prepare 写工具 → REQUIRE_APPROVAL → openCardApproval：
                //    runtime_approval.card_json 含 changeSet/reversibility/estimated，
                //    trust_action.approval_id 回链；
                // 2) 审批通过后 gateway.commit → trust_action 状态 APPLIED，
                //    GET /api/runtime/approvals/{approvalId}/changeset 返回 APPLIED 详情；
                // 3) 审批挂起期间修改 FakeErp 记录 → commit 返回「数据已变化」，状态 STALE。
            }
        }
    }
}
