package com.tioka.orchestration.config;

import com.tioka.orchestration.api.dispatch.OrchActionDispatcher;
import com.tioka.orchestration.api.dispatch.UnsupportedFlowActionDispatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan("com.tioka.orchestration")
public class OrchestrationAutoConfiguration {

    /** M2 flow 占位（约束 F23）；实现类在 api，避免 application 触碰 OrchActionType.FLOW。 */
    @Bean
    OrchActionDispatcher unsupportedFlowActionDispatcher() {
        return new UnsupportedFlowActionDispatcher();
    }
}
