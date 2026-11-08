package com.tioka.ontology.application.pack;

import com.tioka.ontology.api.def.ModelDefinition;

import java.util.List;
import java.util.Optional;

/**
 * 领域包 SPI（ADR-036 / M3-03 §10）。本阶段落在 ont-biz/pack；正式对外契约后续迁 ontology-api。
 */
public interface DomainPack {

    String packKey();

    String version();

    ModelDefinition definition();

    PromptBundle prompts();

    List<ModelValidator> validators();

    Optional<FoldingEngine> folding();

    List<ProjectionHook> projections();

    List<CelFunction> celFunctions();
}
