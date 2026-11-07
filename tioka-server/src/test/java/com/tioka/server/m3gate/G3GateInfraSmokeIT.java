package com.tioka.server.m3gate;

import com.tioka.server.m3gate.biz.LeaseBizStubServer;
import com.tioka.server.m3gate.mail.SmtpCaptureStub;
import com.tioka.server.m3gate.ocr.OcrStubServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENG-04d 冒烟骨架。等 PK-01（GateCluster / TestContainers）合入后去掉 @Disabled，
 * 并迁入 gate 包与双实例编排对齐。
 */
@Disabled("等待 PK-01 闸门基座合入后再接 GateCluster")
class G3GateInfraSmokeIT {

    @Test
    void stubsStart() throws Exception {
        try (LeaseBizStubServer biz = LeaseBizStubServer.start();
             OcrStubServer ocr = OcrStubServer.start();
             SmtpCaptureStub smtp = SmtpCaptureStub.start()) {
            assertFalse(biz.baseUrl().isBlank());
            assertFalse(ocr.baseUrl().isBlank());
            assertTrue(smtp.smtpPort() > 0);
            biz.emitSubmitted("C-DEMO", "S001");
            assertFalse(biz.events().isEmpty());
        }
    }
}
