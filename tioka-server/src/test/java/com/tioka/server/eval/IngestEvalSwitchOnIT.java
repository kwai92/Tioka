package com.tioka.server.eval;

import com.tioka.eval.api.EvalFeedbackApi;
import com.tioka.eval.api.TrajectoryExportApi;
import com.tioka.eval.application.DatasetService;
import com.tioka.eval.application.EvalFeedbackService;
import com.tioka.eval.evaluator.EvaluatorRegistry;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-13 装配安全自检：双开关（tioka.knowledge.ingest.async.enabled / tioka.eval.enabled）
 * 打开时上下文能起且新服务可注入（C-8 清单；关闭态由 RuntimeApiContractTest 全上下文覆盖）。
 * C-14 扩展：EVO-02 数据集/评估器 SPI 随主开关装配；批次评测 BatchEvalService 仅随
 * {@code tioka.eval.batch.enabled} 装配（本类保持子开关关闭，断言其缺席）。
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

    /** C-14：eval 主开关开、批次子开关关 —— 数据集/SPI 就绪，批次执行器零注册。 */
    @Test
    void datasetAndSpiReadyButBatchExecutorAbsentWhenSubSwitchOff() {
        assertNotNull(ctx.getBean(DatasetService.class));
        assertNotNull(ctx.getBean(EvaluatorRegistry.class));
        assertEquals(6, ctx.getBean(EvaluatorRegistry.class).byTypeMapSize(),
                "内置六个评估器注册（C-16 起含 recall_at_5 / mrr）");
        assertFalse(ctx.containsBean("batchEvalService"),
                "tioka.eval.batch.enabled=false 时 BatchEvalService 不得注册");
        assertFalse(ctx.containsBean("evalRunController"),
                "批次子开关关闭时批次 Controller 不得注册");
    }

    private static void assertEquals(int expected, int actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }
}
