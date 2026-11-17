package com.tioka.model.application.profile;

import com.tioka.model.api.profile.StructuredCapabilityQueryApi;
import com.tioka.model.infrastructure.ModelStructuredCapabilityRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * SCH-06（ADR-066 §4；V177）：能力记录的默认查询实现。
 *
 * <p>执行路径（{@code DefaultModelGateway#resolveCapability} 与
 * {@code StructuredOutputPlanService#capabilityOf}）经
 * {@code ObjectProvider<StructuredCapabilityQueryApi>} 弱依赖本 Bean；未登记模型返回
 * {@link Optional#empty()}，由调用方按 {@code StructuredOutputCapability#legacy} 兜底。
 */
@Service
public class StructuredCapabilityQueryService implements StructuredCapabilityQueryApi {

    private final ModelStructuredCapabilityRepository repository;

    public StructuredCapabilityQueryService(ModelStructuredCapabilityRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CapabilityRecord> current(String modelRef) {
        return repository.find(modelRef);
    }
}
