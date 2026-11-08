package com.tioka.server.gate.support;

import com.tioka.server.gate.support.biz.LeaseBizStubServer;
import com.tioka.server.gate.support.embedding.ChineseEmbeddingContainer;
import com.tioka.server.gate.support.erp.FakeErpMultiStepScenarios;
import com.tioka.server.gate.support.mail.SmtpCaptureStub;
import com.tioka.server.gate.support.ocr.OcrStubServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ENG-04d 桩与多步场景本地验收（不依赖 -Pgate / Docker Mailpit）。 */
class M3GateStubsTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void leaseBizGetContractAndIdempotentDraft() throws Exception {
        try (LeaseBizStubServer biz = LeaseBizStubServer.start()) {
            HttpResponse<String> get = http.send(
                    HttpRequest.newBuilder(URI.create(biz.baseUrl() + "/api/contracts/C-DEMO")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, get.statusCode());
            assertTrue(get.body().contains("C-DEMO"));

            HttpRequest post = HttpRequest.newBuilder(URI.create(biz.baseUrl() + "/api/contracts/C-DEMO/model-drafts"))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "k-1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"contentHash\":\"h\",\"draft\":{}}"))
                    .build();
            HttpResponse<String> a = http.send(post, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> b = http.send(post, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, a.statusCode());
            assertEquals(a.body(), b.body());
        }
    }

    @Test
    void ocrUnknownShaFailsExplicitly() throws Exception {
        try (OcrStubServer ocr = OcrStubServer.start()) {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(ocr.baseUrl() + "/ocr/recognize"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"sha256\":\"unknown\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, resp.statusCode());
            assertTrue(resp.body().contains("OCR_FIXTURE_MISS"));
        }
    }

    @Test
    void smtpCaptureWait() throws Exception {
        try (SmtpCaptureStub smtp = SmtpCaptureStub.start()) {
            smtp.accept("lawyer@example.com", "review", "hello");
            var m = smtp.waitForMessage("lawyer@", Duration.ofSeconds(1));
            assertEquals("review", m.subject());
        }
    }

    @Test
    void fakeErpFiveStepsRestoreAndFaultCombos() {
        FakeErpMultiStepScenarios.FiveStepResult five = FakeErpMultiStepScenarios.runFiveWriteoffs();
        FakeErpMultiStepScenarios.compensateAllReverse(five);
        assertEquals("ar_s3", FakeErpMultiStepScenarios.compensateFailureStopsAtThird());
        FakeErpMultiStepScenarios.beforeMutatedRejectsApply();
    }

    @Test
    void chineseEmbeddingAndRerank() throws Exception {
        try (ChineseEmbeddingContainer emb = ChineseEmbeddingContainer.start()) {
            assertEquals(ChineseEmbeddingContainer.DEFAULT_DIM, emb.embedSmoke("华东租金"));
            assertEquals(0, emb.rerankTopIndex("华东", "华东条款", "华南条款"));
        }
    }
}
