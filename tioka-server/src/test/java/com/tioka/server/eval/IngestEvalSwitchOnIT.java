package com.tioka.server.eval;

import com.tioka.eval.api.EvalFeedbackApi;
import com.tioka.eval.api.TrajectoryExportApi;
import com.tioka.eval.application.EvalFeedbackService;
import com.tioka.knowledge.application.KnowledgeIngestJobConsumer;
import com.tioka.knowledge.application.KnowledgeIngestJobService;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * C-13 装配安全自检：双开关（tioka.knowledge.ingest.async.enabled / tioka.eval.enabled）
 * 打开时上下文能起且新服务可注入（C-8 清单；关闭态由 RuntimeApiContractTest 全上下文覆盖）。
 */
@SpringBootTest
@Tag("it")
class IngestEvalSwitchOnIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.knowledge.ingest.async.enabled", () -> "true");
        registry.add("tioka.eval.enabled", () -> "true");
    }

    @Autowired ConfigurableApplicationContext ctx;

    @Test
    void contextBootsWithBothSwitchesOn() {
        assertNotNull(ctx.getBean(KnowledgeIngestJobService.class));
        assertNotNull(ctx.getBean(KnowledgeIngestJobConsumer.class));
        assertNotNull(ctx.getBean(EvalFeedbackApi.class));
        assertNotNull(ctx.getBean(EvalFeedbackService.UndoFeedbackConsumer.class));
        assertNotNull(ctx.getBean(EvalFeedbackService.LeaseReviewFeedbackConsumer.class));
        assertNotNull(ctx.getBean(TrajectoryExportApi.class));
    }
}
