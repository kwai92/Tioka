package com.tioka.ontology.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 值索引遍历（V172 ont_value_index 重建）：ValueCell 状态产出、数组身份路径、
 * 通配聚合、状态存储口径（NA ≤ VARCHAR(12)）。
 */
class ValueIndexWalkerTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode model(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 遍历产出路径与状态() {
        JsonNode root = model("""
                {
                  "feeItems": [
                    {"x-tioka-identity": "RENT@S1",
                     "sched": {"baseAmount": {"value": 10000, "status": "explicit"}},
                     "abate": {"freeMonths": {"value": 0, "status": "unknown"}}},
                    {"type": "DEPOSIT",
                     "amount": {"value": 2, "status": "review"}}
                  ],
                  "plain": {"leaf": 42}
                }
                """);
        List<ValueIndexWalker.IndexPath> entries = ValueIndexWalker.walk(root);
        Map<String, ValueIndexWalker.IndexPath> byPath = new java.util.LinkedHashMap<>();
        for (ValueIndexWalker.IndexPath e : entries) {
            byPath.put(e.path(), e);
        }
        // ValueCell 叶子：状态归一化
        ValueIndexWalker.IndexPath amount = byPath.get("m.feeItems[RENT@S1].sched.baseAmount");
        assertEquals(ModelPathResolver.ValueStatus.KNOWN, amount.status());
        assertEquals("10000", amount.valueText());
        ValueIndexWalker.IndexPath freeMonths = byPath.get("m.feeItems[RENT@S1].abate.freeMonths");
        assertEquals(ModelPathResolver.ValueStatus.UNKNOWN, freeMonths.status());
        ValueIndexWalker.IndexPath deposit = byPath.get("m.feeItems[DEPOSIT].amount");
        assertEquals(ModelPathResolver.ValueStatus.REVIEW, deposit.status());
        // 普通对象叶子：KNOWN
        ValueIndexWalker.IndexPath leaf = byPath.get("m.plain.leaf");
        assertEquals(ModelPathResolver.ValueStatus.KNOWN, leaf.status());
        assertEquals("42", leaf.valueText());
    }

    @Test
    void 状态存储口径() {
        // NA 缩写 ≤ VARCHAR(12)
        assertTrue(ModelPathResolver.ValueStatus.NOT_APPLICABLE.stored().length() <= 12);
        assertTrue(ModelPathResolver.ValueStatus.NOT_APPLICABLE.name().length() > 12);
    }

    @Test
    void 身份缺失时用下标() {
        JsonNode root = model("""
                {"segments": [{"amount": {"value": 1, "status": "explicit"}},
                              {"amount": {"value": 2, "status": "explicit"}}]}
                """);
        List<ValueIndexWalker.IndexPath> entries = ValueIndexWalker.walk(root);
        List<String> paths = entries.stream().map(ValueIndexWalker.IndexPath::path).toList();
        assertTrue(paths.contains("m.segments[0].amount"));
        assertTrue(paths.contains("m.segments[1].amount"));
    }

    @Test
    void 空模型产出空() {
        JsonNode root = model("{}");
        assertTrue(ValueIndexWalker.walk(root).isEmpty());
        assertTrue(ValueIndexWalker.walk(null).isEmpty());
    }
}
