package com.tioka.ontology.application.pack;

import java.util.function.Function;

/**
 * 领域包注册的 CEL 函数（ADR-036 L168：领域包可以注册更多平台内置函数之外的函数）。
 * 实现为纯函数（domain-pack 权限仅 cpu）；以全局 / 成员两种调用形态暴露，接收者作为第一参。
 *
 * @param name           函数名，如 {@code freeMonths}
 * @param description    描述
 * @param implementation 实现（入参：全局形态 null / 成员形态接收者值；返回值参与 CEL 求值）
 */
public record CelFunction(String name, String description, Function<Object, Object> implementation) {

    public CelFunction {
        if (description == null) {
            description = "";
        }
    }

    /** 无实现占位（仅登记名称；注册到 CEL 环境时跳过）。 */
    public CelFunction(String name, String description) {
        this(name, description, null);
    }
}
