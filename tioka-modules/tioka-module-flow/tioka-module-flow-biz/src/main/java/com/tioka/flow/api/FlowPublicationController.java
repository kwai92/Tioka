package com.tioka.flow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.ApiResponse;
import com.tioka.flow.application.FlowPublicationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/flows/publications")
public class FlowPublicationController {
    private final FlowPublicationService publicationService;
    private final ObjectMapper mapper;

    public FlowPublicationController(FlowPublicationService publicationService, ObjectMapper mapper) {
        this.mapper = mapper;
        this.publicationService = publicationService;
    }

    @PostMapping("/tools")
    public ApiResponse<String> asTool(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(publicationService.publishAsTool(body.get("versionId"), body.get("toolName")));
    }

    @PostMapping("/internal/{versionId}")
    public ApiResponse<Map<String, Object>> internal(@PathVariable String versionId, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(publicationService.invokeInternalApi(versionId, body));
    }

    @PostMapping("/webhook/{versionId}")
    public ApiResponse<Map<String, Object>> webhook(@PathVariable String versionId,
                                                    @RequestHeader(value = "X-Signature", required = false) String signature,
                                                    @RequestHeader(value = "X-Tioka-Signature", required = false) String tiokaSignature,
                                                    @RequestBody(required = false) Map<String, Object> body) throws Exception {
        String sig = signature != null ? signature : tiokaSignature;
        String payload = body == null ? "{}" : mapper.writeValueAsString(body);
        publicationService.verifyWebhook(sig, payload);
        return ApiResponse.ok(publicationService.invokeInternalApi(versionId, body));
    }
}
