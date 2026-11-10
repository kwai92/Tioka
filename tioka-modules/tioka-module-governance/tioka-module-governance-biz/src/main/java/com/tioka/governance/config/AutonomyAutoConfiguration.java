package com.tioka.governance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.governance.api.autonomy.AutonomyApi;
import com.tioka.governance.api.autonomy.AutonomyGate;
import com.tioka.governance.application.autonomy.AutonomyChangeNotifier;
import com.tioka.governance.application.autonomy.DefaultAutonomyGate;
import com.tioka.governance.application.autonomy.DefaultAutonomyService;
import com.tioka.governance.application.autonomy.SamplingPolicy;
import com.tioka.governance.infrastructure.autonomy.TrustAutonomyRepository;
import com.tioka.governance.infrastructure.mapper.TrustAutonomyEventMapper;
import com.tioka.governance.infrastructure.mapper.TrustAutonomyMapper;
import com.tioka.governance.infrastructure.mapper.TrustAutonomyStatsMapper;
import com.tioka.system.api.audit.AuditApi;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * TRU-09 自治等级装配（ADR-034 / M3-05 §7）。默认开启：
 * {@code tioka.trust.autonomy.enabled=false} 关闭（第 10 步判定退回 agent-config 审批策略，
 * 切换口径登记 M3-C4 设计笔记）。
 */
@AutoConfiguration
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.trust.autonomy", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class AutonomyAutoConfiguration {

    @Bean
    TrustAutonomyRepository trustAutonomyRepository(TrustAutonomyMapper levels,
                                                    TrustAutonomyEventMapper events,
                                                    TrustAutonomyStatsMapper stats) {
        return new TrustAutonomyRepository(levels, events, stats);
    }

    @Bean
    @ConditionalOnMissingBean(AutonomyGate.class)
    AutonomyGate autonomyGate() {
        return new DefaultAutonomyGate();
    }

    @Bean
    AutonomyChangeNotifier autonomyChangeNotifier(ObjectProvider<EventPublisher> publisher) {
        EventPublisher p = publisher.getIfAvailable();
        return p == null
                ? new AutonomyChangeNotifier(payload -> { }, true)
                : new AutonomyChangeNotifier(p);
    }

    /** 事后抽检 10%（ADR-034「事后抽检」默认比例）；空间可调在 M4。 */
    @Bean
    SamplingPolicy autonomySamplingPolicy() {
        return new SamplingPolicy.Percentage(10);
    }

    @Bean
    AutonomyApi autonomyApi(TrustAutonomyRepository repository,
                            AutonomyChangeNotifier notifier,
                            SamplingPolicy sampler,
                            ObjectProvider<AuditApi> auditApi,
                            ObjectMapper mapper) {
        return new DefaultAutonomyService(repository, notifier, sampler, auditApi, mapper);
    }
}
