package com.tioka.framework.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void differentKeyOrderSameHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", 2);
        b.put("b", 1);
        assertEquals(CanonicalJson.sha256Hex(mapper, a), CanonicalJson.sha256Hex(mapper, b));
        assertEquals("{\"a\":2,\"b\":1}", CanonicalJson.canonicalize(mapper, a));
    }

    @Test
    void differentNumberFormatSameHash() throws Exception {
        var n1 = mapper.readTree("{\"n\":1.0}");
        var n2 = mapper.readTree("{\"n\":1.00}");
        assertEquals(CanonicalJson.sha256Hex(mapper, n1), CanonicalJson.sha256Hex(mapper, n2));
    }
}
