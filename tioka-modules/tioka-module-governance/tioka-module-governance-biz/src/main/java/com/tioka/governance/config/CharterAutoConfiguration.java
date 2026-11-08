package com.tioka.governance.config;

import com.tioka.governance.api.charter.CharterEventWriter;
import com.tioka.governance.application.charter.DefaultCharterEventWriter;
import com.tioka.governance.infrastructure.charter.TrustCharterEventRepository;
import com.tioka.governance.infrastructure.mapper.TrustCharterEventMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * TRU-01 授权书基础设施。默认关闭：{@code tioka.trust.charter.enabled=true} 才装配。
 * 发布校验在 agent-biz {@code CharterPublishCheck}；本配置装配事件写入等 charter 包组件。
 */
@AutoConfiguration
@MapperScan("com.tioka.governance.infrastructure.mapper")
@ConditionalOnProperty(prefix = "tioka.trust.charter", name = "enabled", havingValue = "true")
public class CharterAutoConfiguration {

    @Bean
    TrustCharterEventRepository trustCharterEventRepository(TrustCharterEventMapper mapper) {
        return new TrustCharterEventRepository(mapper);
    }

    @Bean
    CharterEventWriter charterEventWriter(TrustCharterEventRepository events) {
        return new DefaultCharterEventWriter(events);
    }
}
