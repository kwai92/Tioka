package com.tioka.connector.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.support.DirectEgress;
import com.tioka.connector.support.FeishuOpenApiStub;
import com.tioka.connector.support.InMemoryFeishuDocRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书文档增量同步契约：OpenAPI 回放 + 内存镜像（本包不可改 pom，故不以 Testcontainers PG 替代）。
 */
class FeishuDocSyncIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pullIncrementalWritesLocalMirror() throws Exception {
        try (FeishuOpenApiStub stub = FeishuOpenApiStub.start()) {
            stub.enqueueTokenFromResource("feishu/tenant_access_token.json");
            stub.enqueueRawContent(200, read("feishu/docx_raw_content.json"));

            InMemoryFeishuDocRepository docs = new InMemoryFeishuDocRepository();
            FeishuDocSyncService sync = new FeishuDocSyncService(
                    docs, new DirectEgress(), "cli_mock", "secret", stub.baseUrl(), mapper);

            var mapping = sync.map("local-1", "doccn_mock_001");
            String content = sync.pull(mapping.id());

            assertTrue(content.contains("Tioka sync sample"));
            assertEquals(content, docs.findPushContent("feishu://doccn_mock_001"));
        }
    }

    @Test
    void pushCreatesPlaceholderAndUpsertsMirror() throws Exception {
        try (FeishuOpenApiStub stub = FeishuOpenApiStub.start()) {
            stub.enqueueTokenFromResource("feishu/tenant_access_token.json");
            stub.enqueueCreateDocument(200, read("feishu/docx_create_ok.json"));

            InMemoryFeishuDocRepository docs = new InMemoryFeishuDocRepository();
            FeishuDocSyncService sync = new FeishuDocSyncService(
                    docs, new DirectEgress(), "cli_mock", "secret", stub.baseUrl(), mapper);

            var mapping = sync.map("local-2", "doccn_push_target");
            String remote = sync.push(mapping.id(), "Title line\nbody");

            assertEquals("feishu://doccn_push_target", remote);
            assertEquals("Title line\nbody", docs.findPushContent(remote));
            assertEquals(1, stub.docBodies().stream().filter(b -> b.startsWith("POST")).count());
        }
    }

    @Test
    void deleteLocalMirrorFallsBackToEmptyOnPull() {
        InMemoryFeishuDocRepository docs = new InMemoryFeishuDocRepository();
        FeishuDocSyncService sync = new FeishuDocSyncService(
                docs, new DirectEgress(), "", "", "https://open.feishu.cn", mapper);

        var mapping = sync.map("local-3", "local-token");
        sync.push(mapping.id(), "will-delete");
        docs.deletePush("feishu://local-token");

        assertEquals("", sync.pull(mapping.id()));
        assertEquals("", sync.rawContent("local-token"));
    }

    private static String read(String classpath) throws Exception {
        try (var in = FeishuDocSyncIT.class.getClassLoader().getResourceAsStream(classpath)) {
            return new String(in.readAllBytes());
        }
    }
}
