package com.tioka.model.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.profile.StructuredCapabilityQueryApi.CapabilityRecord;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputCapability.MaxTokensAdvice;
import com.tioka.model.api.profile.StructuredOutputCapability.Stability;
import com.tioka.model.api.profile.StructuredOutputCapability.ThinkingCompat;
import com.tioka.model.api.profile.StructuredOutputMode;
import com.tioka.model.infrastructure.mapper.ModelStructuredCapabilityMapper;
import com.tioka.model.infrastructure.persistence.ModelStructuredCapabilityDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCH-06（ADR-066 §4；V177）：能力记录持久化的 JSONB 编解码往返（离线，Mapper 以 Mockito 桩替）。
 */
class ModelStructuredCapabilityRepositoryImplTest {

    private final ModelStructuredCapabilityMapper mapper = mock(ModelStructuredCapabilityMapper.class);
    private final ModelStructuredCapabilityRepositoryImpl repository =
            new ModelStructuredCapabilityRepositoryImpl(mapper, new ObjectMapper());

    private static final String REF = "qwen@qwen3-max";

    private static CapabilityRecord fullRecord() {
        StructuredOutputCapability capability = new StructuredOutputCapability(
                Set.of(StructuredOutputMode.STRICT_SCHEMA, StructuredOutputMode.JSON_OBJECT, StructuredOutputMode.TOOL),
                Set.of("type", "properties", "required"),
                new StructuredOutputCapability.Limits(6, 120, 32, 16, 20),
                Map.of(StructuredOutputMode.STRICT_SCHEMA, ThinkingCompat.NO,
                        StructuredOutputMode.JSON_OBJECT, ThinkingCompat.YES),
                true,
                true,
                MaxTokensAdvice.ESTIMATE,
                Stability.BETA,
                "refusal",
                "length",
                "2026-11-14T10:00:00+08:00",
                "PASSED");
        return new CapabilityRecord(capability, "v3");
    }

    @Test
    @DisplayName("未登记 / 空 ref → Optional.empty（调用方按 legacy 兜底）")
    void returnsEmptyWhenUnregistered() {
        when(mapper.selectById(REF)).thenReturn(null);
        assertTrue(repository.find(REF).isEmpty());
        assertTrue(repository.find(null).isEmpty());
        assertTrue(repository.find("  ").isEmpty());
    }

    @Test
    @DisplayName("登记 → 读回：能力各字段经 JSONB 文本无损往返")
    void roundTripsCapabilityThroughJsonbColumns() {
        ArgumentCaptor<ModelStructuredCapabilityDO> written = ArgumentCaptor.forClass(ModelStructuredCapabilityDO.class);
        repository.put(REF, fullRecord());
        verify(mapper).upsert(written.capture());
        ModelStructuredCapabilityDO row = written.getValue();

        assertEquals(REF, row.getModelRef());
        assertEquals("v3", row.getVersion());
        // JSONB 列以文本承载（写入经 XML `::jsonb` 转型）
        assertTrue(row.getModes().contains("STRICT_SCHEMA") && row.getModes().contains("TOOL"),
                "modes 须序列化为方式名数组：" + row.getModes());
        assertTrue(row.getThinkingCompat().contains("\"STRICT_SCHEMA\":\"NO\""), row.getThinkingCompat());

        when(mapper.selectById(REF)).thenReturn(row);
        Optional<CapabilityRecord> found = repository.find(REF);

        assertTrue(found.isPresent());
        CapabilityRecord record = found.get();
        assertEquals("v3", record.version());
        assertEquals(fullRecord().capability(), record.capability());
    }

    @Test
    @DisplayName("limits 缺省为 null（JSONB 可空）；枚举缺省回落内置默认")
    void handlesNullableLimitsAndDefaults() {
        ModelStructuredCapabilityDO row = new ModelStructuredCapabilityDO();
        row.setModelRef(REF);
        row.setVersion("v1");
        row.setModes("[\"PROMPT\"]");
        row.setKeywords(null);
        row.setLimits(null);
        row.setThinkingCompat(null);
        row.setMaxTokensAdvice(null);
        row.setStability(null);
        when(mapper.selectById(REF)).thenReturn(row);

        CapabilityRecord record = repository.find(REF).orElseThrow();
        StructuredOutputCapability cap = record.capability();
        assertEquals(Set.of(StructuredOutputMode.PROMPT), cap.modes());
        assertEquals(Set.of(), cap.keywords());
        assertNull(cap.limits());
        assertEquals(Map.of(), cap.thinkingCompat());
        assertFalse(cap.multimodalDowngrade());
        assertEquals(MaxTokensAdvice.UNSET, cap.maxTokensAdvice());
        assertEquals(Stability.GA, cap.stability());
    }
}
