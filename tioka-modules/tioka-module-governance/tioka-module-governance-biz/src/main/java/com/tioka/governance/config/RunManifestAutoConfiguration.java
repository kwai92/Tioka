package com.tioka.governance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.governance.api.manifest.RunManifestQuery;
import com.tioka.governance.api.manifest.RunManifestWriter;
import com.tioka.governance.api.timeline.TimelineProjector;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.application.manifest.DefaultRunManifestQuery;
import com.tioka.governance.application.manifest.DefaultRunManifestWriter;
import com.tioka.governance.application.timeline.RunManifestTimelineRebuildSource;
import com.tioka.governance.infrastructure.manifest.RunManifestRepository;
import com.tioka.governance.infrastructure.mapper.RunManifestMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * OBS-06 运行清单装配（ADR-075 §3）。默认开启：{@code tioka.obs.run-manifest.enabled=false} 关闭。
 * <p>{@code RunManifestMapper} 由 {@code MybatisPlusConfiguration} 的全局 {@code @MapperScan} 注册，
 * 此处不再重复声明。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "tioka.obs.run-manifest", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class RunManifestAutoConfiguration {

    @Bean
    RunManifestRepository runManifestRepository(RunManifestMapper mapper) {
        return new RunManifestRepository(mapper);
    }

    /** 事件出口缺席（离线单测）时只落库不发布。 */
    @Bean
    @ConditionalOnMissingBean(RunManifestWriter.class)
    RunManifestWriter runManifestWriter(RunManifestRepository repository,
                                        ObjectProvider<EventPublisher> publisher,
                                        PrincipalHolder principals,
                                        ObjectMapper mapper,
                                        ObjectProvider<TimelineProjector> timelineProjector) {
        return new DefaultRunManifestWriter(repository, publisher.getIfAvailable(), principals, mapper,
                timelineProjector.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean(RunManifestQuery.class)
    RunManifestQuery runManifestQuery(RunManifestRepository repository) {
        return new DefaultRunManifestQuery(repository);
    }

    /**
     * OBS-08：运行清单的时间线重建来源（ADR-075 §2「可按时间段重建」）。
     * 由 {@code TimelineAutoConfiguration} 的 {@code ObjectProvider<TimelineRebuildSource>} 收集。
     */
    @Bean
    TimelineRebuildSource runManifestTimelineRebuildSource(RunManifestRepository repository) {
        return new RunManifestTimelineRebuildSource(repository);
    }
}
