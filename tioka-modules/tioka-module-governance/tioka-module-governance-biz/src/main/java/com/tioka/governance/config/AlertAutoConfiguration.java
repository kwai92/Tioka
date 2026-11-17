package com.tioka.governance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.alert.AlertSignalProvider;
import com.tioka.governance.application.alert.AlertEmitter;
import com.tioka.governance.application.alert.AlertEngine;
import com.tioka.governance.application.alert.AlertEvaluator;
import com.tioka.governance.application.alert.AlertProperties;
import com.tioka.governance.application.alert.AlertRuleService;
import com.tioka.governance.infrastructure.alert.AlertChangeLog;
import com.tioka.governance.infrastructure.alert.OpsAlertRepository;
import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.mapper.OpsAlertEventMapper;
import com.tioka.governance.infrastructure.mapper.OpsAlertRuleMapper;
import com.tioka.system.api.notify.NotificationPort;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GOV-13 告警装配（ADR-041 §5.3）。默认开启；紧急开关 {@code tioka.alerts.enabled=false} 关闭
 * 求值与告警接口（{@code AlertController} 类级条件与之联动）。
 *
 * <p>求值引擎按 {@code tioka.alerts.evaluate-interval-ms}（默认 30s）调度，多实例经 Redis
 * SETNX 锁互斥；信号源 {@link AlertSignalProvider} 由承载数据的模块提供，缺席即该条件无数据；
 * 通知经 {@link NotificationPort}（NTF-01）可选外发，缺席时只入事件。
 */
@AutoConfiguration
@EnableScheduling
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.alerts", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AlertAutoConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "tioka.alerts")
    AlertProperties alertProperties() {
        return new AlertProperties();
    }

    @Bean
    AlertChangeLog alertChangeLog(CfgChangeMapper changes) {
        return new AlertChangeLog(changes);
    }

    @Bean
    OpsAlertRepository opsAlertRepository(OpsAlertRuleMapper rules, OpsAlertEventMapper events) {
        return new OpsAlertRepository(rules, events);
    }

    @Bean
    AlertEngine alertEngine() {
        return new AlertEngine();
    }

    @Bean
    AlertRuleService alertRuleService(OpsAlertRepository repository,
                                      AlertChangeLog changeLog,
                                      AlertProperties props,
                                      PrincipalHolder principals,
                                      ObjectMapper objectMapper) {
        return new AlertRuleService(repository, changeLog, props, principals, objectMapper);
    }

    @Bean
    AlertEmitter alertEmitter(OpsAlertRepository repository,
                              ObjectProvider<NotificationPort> notifications,
                              AlertProperties props) {
        return new AlertEmitter(repository, notifications, props);
    }

    @Bean
    AlertEvaluator alertEvaluator(OpsAlertRepository repository,
                                  AlertRuleService ruleService,
                                  ObjectProvider<AlertSignalProvider> providers,
                                  AlertEngine engine,
                                  AlertEmitter emitter,
                                  ObjectProvider<StringRedisTemplate> redis,
                                  AlertProperties props,
                                  PrincipalHolder principals) {
        return new AlertEvaluator(repository, ruleService, providers.orderedStream().toList(),
                engine, emitter, redis, props, principals);
    }
}
