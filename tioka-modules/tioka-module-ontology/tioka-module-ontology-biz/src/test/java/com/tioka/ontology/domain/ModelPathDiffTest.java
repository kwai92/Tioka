package com.tioka.ontology.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径级差异（M3-03 §7）：ADD/REMOVE/CHANGE、数组 x-tioka-identity 身份对齐
 * （顺序变化不算差异）、无身份按下标、ValueCell 整 cell 比较。
 */
class ModelPathDiffTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 字段增删改() {
        JsonNode from = json("""
                {"keep": 1, "change": {"value": 10, "status": "explicit"}, "gone": 3}
                """);
        JsonNode to = json("""
                {"keep": 1, "change": {"value": 20, "status": "explicit"}, "added": 4}
                """);
        List<ModelPathDiff.Entry> diff = ModelPathDiff.diff(from, to);
        List<String> rendered = diff.stream().map(e -> e.path() + ":" + e.op()).toList();
        assertTrue(rendered.contains("m.gone:REMOVE"), () -> String.valueOf(rendered));
        assertTrue(rendered.contains("m.added:ADD"), () -> String.valueOf(rendered));
        assertTrue(rendered.contains("m.change:CHANGE"), () -> String.valueOf(rendered));
        assertEquals(3, diff.size());
        // from / to 记录整 cell
        ModelPathDiff.Entry change = diff.stream()
                .filter(e -> e.path().equals("m.change")).findFirst().orElseThrow();
        assertEquals(10, change.from().get("value").asLong());
        assertEquals(20, change.to().get("value").asLong());
    }

    @Test
    void 身份对齐_顺序变化不算差异() {
        JsonNode from = json("""
                {"feeItems": [
                  {"x-tioka-identity": "RENT@S1", "amount": {"value": 1, "status": "explicit"}},
                  {"x-tioka-identity": "DEP", "amount": {"value": 2, "status": "explicit"}}]}
                """);
        JsonNode to = json("""
                {"feeItems": [
                  {"x-tioka-identity": "DEP", "amount": {"value": 2, "status": "explicit"}},
                  {"x-tioka-identity": "RENT@S1", "amount": {"value": 1, "status": "explicit"}}]}
                """);
        assertTrue(ModelPathDiff.diff(from, to).isEmpty());
    }

    @Test
    void 身份对齐_元素值变化与增删() {
        JsonNode from = json("""
                {"feeItems": [
                  {"x-tioka-identity": "RENT@S1", "amount": {"value": 1, "status": "explicit"}}]}
                """);
        JsonNode to = json("""
                {"feeItems": [
                  {"x-tioka-identity": "RENT@S1", "amount": {"value": 9, "status": "explicit"}},
                  {"x-tioka-identity": "DEP", "amount": {"value": 2, "status": "explicit"}}]}
                """);
        List<ModelPathDiff.Entry> diff = ModelPathDiff.diff(from, to);
        List<String> rendered = diff.stream().map(e -> e.path() + ":" + e.op()).toList();
        assertTrue(rendered.contains("m.feeItems[RENT@S1].amount:CHANGE"), () -> String.valueOf(rendered));
        assertTrue(rendered.contains("m.feeItems[DEP]:ADD"), () -> String.valueOf(rendered));
        assertEquals(2, diff.size());
    }

    @Test
    void 无身份数组按下标对齐() {
        JsonNode from = json("""
                {"segments": [{"amount": {"value": 1, "status": "explicit"}},
                              {"amount": {"value": 2, "status": "explicit"}}]}
                """);
        JsonNode to = json("""
                {"segments": [{"amount": {"value": 1, "status": "explicit"}},
                              {"amount": {"value": 5, "status": "explicit"}}]}
                """);
        List<ModelPathDiff.Entry> diff = ModelPathDiff.diff(from, to);
        assertEquals(1, diff.size());
        assertEquals("m.segments[1].amount", diff.get(0).path());
        assertEquals(ModelPathDiff.Op.CHANGE, diff.get(0).op());
    }

    @Test
    void ValueCell状态变化也是CHANGE() {
        JsonNode from = json("""
                {"abate": {"freeMonths": {"value": 0, "status": "blank"}}}
                """);
        JsonNode to = json("""
                {"abate": {"freeMonths": {"value": 0, "status": "explicit"}}}
                """);
        List<ModelPathDiff.Entry> diff = ModelPathDiff.diff(from, to);
        assertEquals(1, diff.size());
        assertEquals("m.abate.freeMonths", diff.get(0).path());
    }

    @Test
    void 标量类型变化与相同值无差异() {
        JsonNode same = json("""
                {"a": 1, "b": {"c": "x"}}
                """);
        assertTrue(ModelPathDiff.diff(same, json("""
                {"a": 1, "b": {"c": "x"}}
                """)).isEmpty());
        List<ModelPathDiff.Entry> diff = ModelPathDiff.diff(same, json("""
                {"a": true, "b": {"c": "x"}}
                """));
        assertEquals(1, diff.size());
        assertEquals("m.a", diff.get(0).path());
    }
}
