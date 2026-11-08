package com.tioka.eval.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * 评测模块自动配置空壳（P3-00b）。默认关闭，不改变现有行为。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.eval", name = "enabled", havingValue = "true")
@ComponentScan(basePackages = "com.tioka.eval")
public class EvalAutoConfiguration {
}
