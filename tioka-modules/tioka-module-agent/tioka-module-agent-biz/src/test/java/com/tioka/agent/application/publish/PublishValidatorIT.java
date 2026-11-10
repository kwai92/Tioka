package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.connector.api.dataset.DatasetPolicyQueryApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发布校验数据集检查离线测试（DAT-01 / M3-02）：
 * SERVICE 模式绑定数据集必须 ENFORCE 策略；所有模式校验引用存在。
 * ObjectProvider 用 Mockito；DatasetPolicyQueryApi 用内存实现。
 */
class PublishValidatorIT {

    private final ObjectMapper mapper = new ObjectMapper();

    private static class InMemoryDatasetApi implements DatasetPolicyQueryApi {
        final Map<String, Boolean> enforce = new HashMap<>();

        InMemoryDatasetApi put(String name, boolean hasEnforce) {
            enforce.put(name, hasEnforce);
            return this;
        }

        @Override public boolean exists(String name) { return enforce.containsKey(name); }

        @Override public boolean hasEnforcePolicy(String name) {
            return Boolean.TRUE.equals(enforce.get(name));
        }
    }

    private static DatasetPublishCheck checkWith(DatasetPolicyQueryApi api) {
        @SuppressWarnings("unchecked")
        ObjectProvider<DatasetPolicyQueryApi> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(api);
        return new DatasetPublishCheck(provider);
    }

    private static AgentSpec spec(ObjectNode charter, List<String> datasets) {
        return new AgentSpec(
                1,
                new AgentSpec.Profile("a", null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "t"))),
                new AgentSpec.ModelPolicy("rp", false),
                new AgentSpec.ToolSet(List.of(), List.of(), List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                charter, null, null, null, null, null, null, null, datasets);
    }

    private ObjectNode charter(String actingAs) {
        return mapper.createObjectNode().put("actingAs", actingAs);
    }

    private static List<String> codes(List<PublishCheck.PublishError> errors) {
        return errors.stream().map(PublishCheck.PublishError::code).toList();
    }

    @Test
    void noDatasetsPasses() {
        DatasetPublishCheck check = checkWith(new InMemoryDatasetApi());
        assertTrue(check.check(spec(charter("SERVICE"), List.of()),
                new PublishCheck.PublishContext("agt_1", "u_1", false)).isEmpty());
    }

    @Test
    void refNotFoundRejected() {
        DatasetPublishCheck check = checkWith(new InMemoryDatasetApi().put("ds_ok", true));
        List<PublishCheck.PublishError> errors = check.check(
                spec(charter("DELEGATED"), List.of("ds_ghost")),
                new PublishCheck.PublishContext("agt_1", "u_1", false));
        assertTrue(codes(errors).contains("REF_NOT_FOUND"));

        // 多个引用逐条校验：一个存在一个不存在
        List<PublishCheck.PublishError> errors2 = check.check(
                spec(charter("DELEGATED"), List.of("ds_ok", "ds_ghost")),
                new PublishCheck.PublishContext("agt_1", "u_1", false));
        assertTrue(codes(errors2).contains("REF_NOT_FOUND"));
        assertEqualsPath("/datasets/1", errors2);
    }

    private static void assertEqualsPath(String expected, List<PublishCheck.PublishError> errors) {
        assertTrue(errors.stream().anyMatch(e -> expected.equals(e.path())));
    }

    @Test
    void serviceModeRequiresEnforce() {
        InMemoryDatasetApi api = new InMemoryDatasetApi()
                .put("ds_enforce", true)
                .put("ds_dryrun", false);
        DatasetPublishCheck check = checkWith(api);

        // SERVICE + DRYRUN → 拒
        List<PublishCheck.PublishError> errors = check.check(
                spec(charter("SERVICE"), List.of("ds_dryrun")),
                new PublishCheck.PublishContext("agt_1", "u_1", false));
        assertTrue(codes(errors).contains("SERVICE_MODE_NEEDS_POLICY"));

        // SERVICE + ENFORCE → 通过
        assertTrue(check.check(spec(charter("SERVICE"), List.of("ds_enforce")),
                new PublishCheck.PublishContext("agt_1", "u_1", false)).isEmpty());

        // DELEGATED + DRYRUN → 通过（非 SERVICE 模式不做 ENFORCE 强制）
        assertTrue(check.check(spec(charter("DELEGATED"), List.of("ds_dryrun")),
                new PublishCheck.PublishContext("agt_1", "u_1", false)).isEmpty());

        // 回滚发布同样执行检查（引用面）
        List<PublishCheck.PublishError> rollback = check.check(
                spec(charter("SERVICE"), List.of("ds_ghost")),
                new PublishCheck.PublishContext("agt_1", "u_1", true));
        assertTrue(codes(rollback).contains("REF_NOT_FOUND"));
    }

    @Test
    void apiUnavailableReportsRef() {
        @SuppressWarnings("unchecked")
        ObjectProvider<DatasetPolicyQueryApi> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        DatasetPublishCheck check = new DatasetPublishCheck(empty);

        List<PublishCheck.PublishError> errors = check.check(
                spec(charter("SERVICE"), List.of("ds_any")),
                new PublishCheck.PublishContext("agt_1", "u_1", false));
        assertTrue(codes(errors).contains("REF_UNAVAILABLE"));
        assertEqualsPath("/datasets/0", errors);
    }

    @Test
    void serviceCharterWithEnforceDatasetPasses() {
        InMemoryDatasetApi api = new InMemoryDatasetApi().put("ds_enforce", true);
        DatasetPublishCheck check = checkWith(api);
        // 正常 actingAs=SERVICE + ENFORCE 数据集 → 通过（强制路径由 serviceModeRequiresEnforce 覆盖）
        assertTrue(check.check(spec(charter("SERVICE"), List.of("ds_enforce")),
                new PublishCheck.PublishContext("agt_1", "u_1", false)).isEmpty());
    }
}
