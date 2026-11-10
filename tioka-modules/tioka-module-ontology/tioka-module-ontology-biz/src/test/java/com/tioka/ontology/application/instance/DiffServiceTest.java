package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.domain.ModelPathDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DiffService（应用层入口）→ domain ModelPathDiff 委托一致。
 */
class DiffServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 与domain算法一致() {
        DiffService service = new DiffService();
        JsonNode from = json("""
                {"feeItems": [{"x-tioka-identity": "RENT@S1",
                               "amount": {"value": 1, "status": "explicit"}}]}
                """);
        JsonNode to = json("""
                {"feeItems": [{"x-tioka-identity": "RENT@S1",
                               "amount": {"value": 2, "status": "explicit"},
                               "extra": 1}]}
                """);
        List<ModelPathDiff.Entry> diff = service.diff(from, to);
        assertEquals(ModelPathDiff.diff(from, to), diff);
        assertTrue(diff.stream().anyMatch(e -> e.path().equals("m.feeItems[RENT@S1].amount")
                && e.op() == ModelPathDiff.Op.CHANGE));
    }
}
