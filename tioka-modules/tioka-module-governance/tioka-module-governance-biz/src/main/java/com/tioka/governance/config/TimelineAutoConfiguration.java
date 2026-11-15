package com.tioka.governance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.governance.api.timeline.TimelineProjector;
import com.tioka.governance.api.timeline.TimelineQuery;
import com.tioka.governance.api.timeline.TimelineRebuildSource;
import com.tioka.governance.application.timeline.CfgChangeTimelineRebuildSource;
import com.tioka.governance.application.timeline.DefaultTimelineProjector;
import com.tioka.governance.application.timeline.DefaultTimelineQuery;
import com.tioka.governance.application.timeline.TimelineProjectionConsumer;
import com.tioka.governance.infrastructure.config.CfgChangeRepository;
import com.tioka.governance.infrastructure.mapper.CfgChangeMapper;
import com.tioka.governance.infrastructure.mapper.TimelineEntryMapper;
import com.tioka.governance.infrastructure.timeline.TimelineRepository;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * OBS-08 统一时间线投影装配（ADR-075 §2）。默认开启：{@code tioka.obs.timeline.enabled=false} 关闭。
 * <p>{@code TimelineEntryMapper} 由 {@code MybatisPlusConfiguration} 的全局 {@code @MapperScan} 注册，
 * 此处不重复声明。重建来源 {@code TimelineRebuildSource} 由各来源模块提供，缺失即不参与重建。
 */
@AutoConfiguration
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.obs.timeline", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class TimelineAutoConfiguration {

    @Bean
    TimelineRepository timelineRepository(TimelineEntryMapper mapper) {
        return new TimelineRepository(mapper);
    }

    @Bean
    @ConditionalOnMissingBean(TimelineProjector.class)
    TimelineProjector timelineProjector(TimelineRepository repository) {
        return new DefaultTimelineProjector(repository);
    }

    @Bean
    @ConditionalOnMissingBean(TimelineQuery.class)
    TimelineQuery timelineQuery(TimelineRepository repository,
                                TimelineProjector projector,
                                ObjectProvider<TimelineRebuildSource> rebuildSources) {
        return new DefaultTimelineQuery(repository, projector, rebuildSources.orderedStream().toList());
    }

    @Bean
    TimelineProjectionConsumer timelineProjectionConsumer(TimelineProjector projector) {
        return new TimelineProjectionConsumer(projector);
    }

    /**
     * OBS-08：配置变更的时间线重建来源（ADR-075 §2 主体时间线的灰色背景条目）。
     * 由 {@code ObjectProvider<TimelineRebuildSource>} 收集。
     */
    @Bean
    CfgChangeRepository cfgChangeRepository(CfgChangeMapper mapper) {
        return new CfgChangeRepository(mapper);
    }

    @Bean
    TimelineRebuildSource cfgChangeTimelineRebuildSource(CfgChangeRepository repository,
                                                         ObjectMapper objectMapper) {
        return new CfgChangeTimelineRebuildSource(repository, objectMapper);
    }
}
