package com.tioka.model.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.profile.StructuredCapabilityQueryApi.CapabilityRecord;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputCapability.MaxTokensAdvice;
import com.tioka.model.api.profile.StructuredOutputCapability.Stability;
import com.tioka.model.api.profile.StructuredOutputCapability.ThinkingCompat;
import com.tioka.model.api.profile.StructuredOutputMode;
import com.tioka.model.infrastructure.mapper.ModelStructuredCapabilityMapper;
import com.tioka.model.infrastructure.persistence.ModelStructuredCapabilityDO;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SCH-06（ADR-066 §4；V177）：能力记录的持久化实现。
 *
 * <p>JSONB 列在 DO 中以 String 承载，本类负责能力记录 ↔ JSON 文本的编解码。
 */
@Repository
public class ModelStructuredCapabilityRepositoryImpl implements ModelStructuredCapabilityRepository {

    private static final TypeReference<Set<StructuredOutputMode>> MODES = new TypeReference<>() { };
    private static final TypeReference<Set<String>> KEYWORDS = new TypeReference<>() { };
    private static final TypeReference<Map<StructuredOutputMode, ThinkingCompat>> THINKING =
            new TypeReference<>() { };

    private final ModelStructuredCapabilityMapper mapper;
    private final ObjectMapper json;

    public ModelStructuredCapabilityRepositoryImpl(ModelStructuredCapabilityMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Optional<CapabilityRecord> find(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            return Optional.empty();
        }
        ModelStructuredCapabilityDO row = mapper.selectById(modelRef);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new CapabilityRecord(toCapability(row), row.getVersion()));
    }

    @Override
    public void put(String modelRef, CapabilityRecord record) {
        if (modelRef == null || modelRef.isBlank() || record == null) {
            throw new IllegalArgumentException("modelRef 与能力记录必填");
        }
        StructuredOutputCapability cap = record.capability();
        ModelStructuredCapabilityDO row = new ModelStructuredCapabilityDO();
        row.setModelRef(modelRef);
        row.setVersion(record.version());
        row.setModes(write(cap.modes()));
        row.setKeywords(write(cap.keywords()));
        row.setLimits(cap.limits() == null ? null : write(cap.limits()));
        row.setThinkingCompat(write(cap.thinkingCompat()));
        row.setMultimodalDowngrade(cap.multimodalDowngrade());
        row.setPromptKeyword(cap.promptKeyword());
        row.setMaxTokensAdvice(cap.maxTokensAdvice().name());
        row.setStability(cap.stability().name());
        row.setRefusalSignal(cap.refusalSignal());
        row.setTruncationSignal(cap.truncationSignal());
        row.setVerifiedAt(cap.verifiedAt());
        row.setConformance(cap.conformance());
        mapper.upsert(row);
    }

    private StructuredOutputCapability toCapability(ModelStructuredCapabilityDO row) {
        return new StructuredOutputCapability(
                read(row.getModes(), MODES, Set.of()),
                read(row.getKeywords(), KEYWORDS, Set.of()),
                row.getLimits() == null ? null : read(row.getLimits(), StructuredOutputCapability.Limits.class),
                read(row.getThinkingCompat(), THINKING, Map.of()),
                Boolean.TRUE.equals(row.getMultimodalDowngrade()),
                Boolean.TRUE.equals(row.getPromptKeyword()),
                advice(row.getMaxTokensAdvice()),
                stability(row.getStability()),
                row.getRefusalSignal(),
                row.getTruncationSignal(),
                row.getVerifiedAt(),
                row.getConformance());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("能力记录序列化失败", e);
        }
    }

    private <T> T read(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("能力记录反序列化失败: " + raw, e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("能力记录反序列化失败: " + raw, e);
        }
    }

    private static MaxTokensAdvice advice(String raw) {
        return raw == null || raw.isBlank() ? MaxTokensAdvice.UNSET : MaxTokensAdvice.valueOf(raw);
    }

    private static Stability stability(String raw) {
        return raw == null || raw.isBlank() ? Stability.GA : Stability.valueOf(raw);
    }
}
