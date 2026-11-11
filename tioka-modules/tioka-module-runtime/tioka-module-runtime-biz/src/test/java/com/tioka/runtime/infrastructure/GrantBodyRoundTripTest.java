package com.tioka.runtime.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.ChainHop;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 临时诊断：GrantBody JSON 往返稳定性（C-17）。 */
class GrantBodyRoundTripTest {

    @Test
    void roundTripIsStable() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DelegationGrant grant = new DelegationGrant(
                "grt_x", null, "user:1", null,
                List.of(new ChainHop("av_1", null)),
                Set.of("kb.search", "data.query", "kb.read_chunk", "erp.update", "echo",
                        "agent.delegate", "async.defer", "erp.query", "kb.read_document", "orch.receipt"),
                Set.of("ERP_WRITE"),
                Set.of(), Map.of(), Map.of(), null, Set.of(),
                new Budget(new BigDecimal(10000), null, null),
                Instant.parse("2026-11-11T22:06:31Z"), GrantStatus.ACTIVE, null);
        GrantBody body = new GrantBody(grant, 0L, 1L);
        String json1 = mapper.writeValueAsString(body);
        GrantBody back = mapper.readValue(json1, GrantBody.class);
        String json2 = mapper.writeValueAsString(back);
        assertEquals(json1, json2, "GrantBody JSON 往返必须稳定");
        System.out.println("[diag] json1 tools=" + json1);
        System.out.println("[diag] json2 tools=" + json2);
    }
}
