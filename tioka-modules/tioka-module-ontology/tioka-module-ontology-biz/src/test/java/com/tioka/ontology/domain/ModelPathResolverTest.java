package com.tioka.ontology.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型路径解析（M3-03 §9）：m 根、选择器（['RENT@S1'] / [0] / [*]）、引号剥离、
 * ValueCell 状态归一与聚合（UNKNOWN &gt; BLANK &gt; NA &gt; KNOWN，REVIEW 特殊）。
 */
class ModelPathResolverTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode model(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String MODEL = """
            {
              "feeItems": [
                {"x-tioka-identity": "RENT@S1",
                 "sched": {"baseAmount": {"value": 10000, "status": "explicit",
                                          "sources": [{"unitId": "u1"}]}},
                 "abate": {"freeMonths": {"value": 0, "status": "blank"}}},
                {"type": "DEPOSIT",
                 "amount": {"value": 2, "status": "review"}}
              ],
              "nested": {"deep": {"leaf": 42}}
            }
            """;

    @Test
    void 解析带选择器路径() {
        JsonNode root = model(MODEL);
        JsonNode cell = ModelPathResolver.resolve(root, "m.feeItems['RENT@S1'].sched.baseAmount");
        assertTrue(ModelPathResolver.isValueCell(cell));
        assertEquals(10000, ModelPathResolver.cellValue(cell).asLong());

        // type 字段身份选择器
        JsonNode deposit = ModelPathResolver.resolve(root, "m.feeItems[DEPOSIT].amount");
        assertEquals("review", deposit.get("status").asText());

        // 数字下标选择器
        JsonNode first = ModelPathResolver.resolve(root, "m.feeItems[0].sched.baseAmount");
        assertEquals(10000, ModelPathResolver.cellValue(first).asLong());

        // 深层点路径
        assertEquals(42, ModelPathResolver.resolve(root, "m.nested.deep.leaf").asLong());

        // 不存在 → null
        assertNull(ModelPathResolver.resolve(root, "m.feeItems[RENT@S1].deposit"));
        assertNull(ModelPathResolver.resolve(root, "m.nope.deep"));
    }

    @Test
    void 非法路径抛错() {
        JsonNode root = model(MODEL);
        // 非 m 根
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ModelPathResolver.resolve(root, "feeItems[0].amount"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ModelPathResolver.resolve(root, "x.feeItems"));
        // 纯 m 返回根本身（parse 仅校验根名）
        assertTrue(ModelPathResolver.resolve(root, "m") == root);
    }

    @Test
    void 状态归一与聚合() {
        JsonNode root = model(MODEL);
        assertEquals(ModelPathResolver.ValueStatus.KNOWN,
                ModelPathResolver.statusOf(ModelPathResolver.resolve(root, "m.feeItems['RENT@S1'].sched.baseAmount")));
        assertEquals(ModelPathResolver.ValueStatus.BLANK,
                ModelPathResolver.statusOf(ModelPathResolver.resolve(root, "m.feeItems['RENT@S1'].abate.freeMonths")));
        // review 聚合：子树含 REVIEW 但其余已知 → REVIEW
        assertEquals(ModelPathResolver.ValueStatus.REVIEW,
                ModelPathResolver.statusOf(ModelPathResolver.resolve(root, "m.feeItems[DEPOSIT].amount")));
        // feeItems[0] 子树聚合：known + blank → BLANK（更差者胜）
        assertEquals(ModelPathResolver.ValueStatus.BLANK,
                ModelPathResolver.statusOf(ModelPathResolver.resolve(root, "m.feeItems[0]")));
        // 未知字面量状态 → UNKNOWN
        JsonNode odd = model("""
                {"leaf": {"value": 1, "status": "whatever"}}
                """);
        assertEquals(ModelPathResolver.ValueStatus.UNKNOWN, ModelPathResolver.statusOf(odd.get("leaf")));
        // 值节点 → KNOWN
        assertEquals(ModelPathResolver.ValueStatus.KNOWN, ModelPathResolver.statusOf(odd.get("leaf").get("value")));
    }

    @Test
    void 状态存储口径_NA缩写() {
        assertEquals("KNOWN", ModelPathResolver.ValueStatus.KNOWN.stored());
        assertEquals("NA", ModelPathResolver.ValueStatus.NOT_APPLICABLE.stored());
        assertEquals("UNKNOWN", ModelPathResolver.ValueStatus.UNKNOWN.stored());
    }

    @Test
    void parse段与选择器() {
        List<ModelPathResolver.Seg> segs = ModelPathResolver.parse("m.feeItems['RENT@S1'].sched.baseAmount");
        assertEquals(4, segs.size());
        assertEquals("feeItems", segs.get(1).name());
        assertTrue(segs.get(1).hasSelector());
        assertEquals("RENT@S1", segs.get(1).selector());
        assertFalse(segs.get(2).hasSelector());
        // 无引号选择器同样剥离
        List<ModelPathResolver.Seg> segs2 = ModelPathResolver.parse("m.feeItems[RENT@S1].amount");
        assertEquals("RENT@S1", segs2.get(1).selector());
        // 通配
        List<ModelPathResolver.Seg> segs3 = ModelPathResolver.parse("m.feeItems[*]");
        assertEquals("*", segs3.get(1).selector());
    }

    @Test
    void isValueCell判定() {
        JsonNode root = model(MODEL);
        assertTrue(ModelPathResolver.isValueCell(
                ModelPathResolver.resolve(root, "m.feeItems['RENT@S1'].sched.baseAmount")));
        assertFalse(ModelPathResolver.isValueCell(root.get("nested")));
        assertFalse(ModelPathResolver.isValueCell(null));
    }

    @Test
    void 通配选择器返回数组本身() {
        JsonNode root = model(MODEL);
        JsonNode all = ModelPathResolver.resolve(root, "m.feeItems[*]");
        assertTrue(all.isArray());
        assertEquals(2, all.size());
        // Map.of 避免未使用告警
        assertTrue(Map.of().isEmpty());
    }
}
