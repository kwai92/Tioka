package com.tioka.server.gov;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.id.Ids;
import com.tioka.model.infrastructure.ModelUsageRepository;
import com.tioka.model.infrastructure.persistence.ModelUsageDO;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GOV-05 / 34 号验收：空间月度成本可查。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class CostIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired ModelUsageRepository usages;

    @Test
    void monthlyByWorkspace() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        ZoneId zone = ZoneId.systemDefault();
        LocalDate monthStart = LocalDate.now(zone).withDayOfMonth(1);
        LocalDateTime midMonth = monthStart.plusDays(5).atTime(10, 0);

        insertUsage(admin.workspaceId(), admin.userId(), "sess-a", midMonth, 100, 50, 1_000_000L);
        insertUsage(admin.workspaceId(), admin.userId(), "sess-a", midMonth.plusHours(1), 200, 80, 2_500_000L);
        insertUsage(admin.workspaceId(), admin.userId(), "sess-b", midMonth.plusDays(1), 40, 10, 500_000L);

        String from = monthStart.atStartOfDay(zone).toInstant().toString();
        String to = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().minusMillis(1).toString();

        MvcResult result = mockMvc.perform(admin.auth(get("/api/system/cost/summary"))
                        .param("groupBy", "workspace")
                        .param("from", from)
                        .param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertTrue(data.isArray());
        JsonNode mine = null;
        for (JsonNode row : data) {
            if (admin.workspaceId().equals(row.path("key").asText())) {
                mine = row;
                break;
            }
        }
        assertTrue(mine != null, "应包含当前空间成本行");
        assertEquals(340, mine.path("promptTokens").asLong());
        assertEquals(140, mine.path("completionTokens").asLong());
        assertEquals(4_000_000L, mine.path("costMicros").asLong());
        assertEquals(3, mine.path("calls").asLong());

        String day = midMonth.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        MvcResult trend = mockMvc.perform(admin.auth(get("/api/system/cost/trend"))
                        .param("scopeType", "workspace")
                        .param("scopeId", admin.workspaceId())
                        .param("from", from)
                        .param("to", to)
                        .param("interval", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode points = objectMapper.readTree(trend.getResponse().getContentAsString()).path("data");
        assertTrue(points.isArray() && points.size() >= 1);
        long dayCost = 0;
        for (JsonNode p : points) {
            if (day.equals(p.path("day").asText())) {
                dayCost = p.path("costMicros").asLong();
            }
        }
        assertEquals(3_500_000L, dayCost);

        // 会话成本：直接按 session 汇总（对话不存在走 404）
        mockMvc.perform(admin.auth(get("/api/chat/conversations/{id}/cost", "conv_missing")))
                .andExpect(status().isNotFound());
    }

    private void insertUsage(String workspaceId, String userId, String sessionId,
                             LocalDateTime at, int prompt, int completion, long costMicros) {
        ModelUsageDO row = new ModelUsageDO();
        row.setId(Ids.newId("usage"));
        row.setWorkspaceId(workspaceId);
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setPurpose("CHAT");
        row.setBindingId("bind-it");
        row.setModelRef("gpt-test");
        row.setPromptTokens(prompt);
        row.setCompletionTokens(completion);
        row.setCostMicros(costMicros);
        row.setLatencyMs(12);
        row.setStatus("SUCCESS");
        row.setCreateAt(at);
        row.setCreateBy("cost-it");
        usages.insert(row);
    }
}
