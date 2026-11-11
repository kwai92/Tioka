package com.tioka.eval.evaluator;

import com.tioka.eval.api.spi.Evaluator;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Evaluator SPI 注册表（ADR-031）：Spring 收集容器内全部 {@link Evaluator} 实现，
 * 按 {@link Evaluator#type()} 建索引。内置四个由 EvalAutoConfiguration 注册；
 * 第三方评估器（领域包经 DomainPackLoader 模式装配）注册为额外 bean 即进入本表。
 */
public class EvaluatorRegistry {

    private final Map<String, Evaluator> byType;

    public EvaluatorRegistry(List<Evaluator> evaluators) {
        this.byType = evaluators.stream()
                .collect(Collectors.toMap(e -> e.type().toLowerCase(Locale.ROOT),
                        Function.identity(), (a, b) -> a));
    }

    public Optional<Evaluator> byType(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byType.get(type.trim().toLowerCase(Locale.ROOT)));
    }

    /** 装配自检用：已注册类型数。 */
    public int byTypeMapSize() {
        return byType.size();
    }
}
