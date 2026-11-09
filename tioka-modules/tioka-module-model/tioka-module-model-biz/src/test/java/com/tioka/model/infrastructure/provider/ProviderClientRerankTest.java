package com.tioka.model.infrastructure.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.infrastructure.provider.ProviderClient;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.SecretStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MH-04（GAP-L5-4）：ProviderClient mock 模式 rerank 打分——
 * 字符 bigram 重合度，确定性、可离线断言排序。
 */
class ProviderClientRerankTest {

    private final ProviderClient client = new ProviderClient(
            mock(SecretStore.class), mock(EgressClient.class), true, 8, new ObjectMapper());

    @Test
    void relatedDocumentScoresHigher() throws Exception {
        String query = "如何配置模型预算";
        List<String> docs = List.of("模型预算配置说明：月度 token 上限", "今天天气很好，适合散步");
        List<ProviderClient.RerankHit> hits = client.rerank("mock://openai", null, "m", query, docs);
        assertEquals(2, hits.size(), "mock 模式返回全部候选（排序由网关做）");
        assertEquals(0, hits.get(0).index(), "下标保留原始文档位次");
        assertTrue(hits.get(0).score() > hits.get(1).score(),
                "语义相关的文档得分应更高（query 与 doc0 bigram 重合）");
    }

    @Test
    void unrelatedDocumentScoresZero() {
        double score = ProviderClient.bigramOverlap("abcdefgh", "xyzwvu");
        assertEquals(0.0d, score, 1e-9, "完全无重合 bigram 得分为 0");
    }

    @Test
    void shortInputsScoreZero() {
        assertEquals(0.0d, ProviderClient.bigramOverlap("a", "abcdef"), 1e-9,
                "query 长度 < 2 无 bigram，得 0");
        assertEquals(0.0d, ProviderClient.bigramOverlap("abcdef", ""), 1e-9,
                "空文档得 0");
        assertEquals(0.0d, ProviderClient.bigramOverlap(null, "abcdef"), 1e-9,
                "null 输入得 0");
    }

    @Test
    void identicalTextScoresOne() {
        assertEquals(1.0d, ProviderClient.bigramOverlap("模型预算", "模型预算"), 1e-9,
                "完全相同文本 Jaccard = 1");
    }
}
