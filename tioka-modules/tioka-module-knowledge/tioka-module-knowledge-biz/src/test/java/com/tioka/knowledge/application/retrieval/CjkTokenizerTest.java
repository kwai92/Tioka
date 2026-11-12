package com.tioka.knowledge.application.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** KNW-12：应用层中文分词的 bigram / ASCII / 去重与上限。 */
class CjkTokenizerTest {

    @Test
    void chineseBigramProducesOverlappingTokens() {
        List<String> tokens = CjkTokenizer.tokenize("差旅报销流程");
        assertEquals(List.of("差旅", "旅报", "报销", "销流", "流程"), tokens);
    }

    @Test
    void asciiWordsAndCjkMixed() {
        List<String> tokens = CjkTokenizer.tokenize("A-03-2 货架 分区 编码");
        assertTrue(tokens.contains("a-03-2"));
        assertTrue(tokens.contains("货架"));
        assertTrue(tokens.contains("分区"));
        assertTrue(tokens.contains("编码"));
    }

    @Test
    void singleCjkCharacterIsKept() {
        assertEquals(List.of("伞"), CjkTokenizer.tokenize("伞"));
    }

    @Test
    void deduplicatesAndCapsAtMaxTokens() {
        assertTrue(CjkTokenizer.tokenize("报销 报销 报销").equals(List.of("报销")));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longText.append((char) ('\u4e00' + i));
        }
        assertEquals(CjkTokenizer.MAX_TOKENS,
                CjkTokenizer.tokenize(longText.toString()).size());
    }

    @Test
    void blankInputIsEmpty() {
        assertTrue(CjkTokenizer.tokenize(null).isEmpty());
        assertTrue(CjkTokenizer.tokenize("   ").isEmpty());
    }
}
