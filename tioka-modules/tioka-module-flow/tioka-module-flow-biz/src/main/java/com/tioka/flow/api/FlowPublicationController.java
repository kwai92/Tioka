package com.tioka.flow.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.flow.application.FlowPublicationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/flows/publications")
@RequirePermission(Permissions.FLOW_RUN)
public class FlowPublicationController {
    private final FlowPublicationService publicationService;
    private final ResourceGuard resourceGuard;
    private final PrincipalHolder principals;

    public FlowPublicationController(FlowPublicationService publicationService,
                                     ResourceGuard resourceGuard,
                                     PrincipalHolder principals) {
        this.publicationService = publicationService;
        this.resourceGuard = resourceGuard;
        this.principals = principals;
    }

    @PostMapping("/tools")
    @Audited(action = "flow.publication.as_tool", resourceType = "flow")
    public ApiResponse<String> asTool(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(publicationService.publishAsTool(body.get("versionId"), body.get("toolName")));
    }

    @PostMapping("/internal/{versionId}")
    public ApiResponse<Map<String, Object>> internal(@PathVariable String versionId, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(publicationService.invokeInternalApi(versionId, body));
    }

    /** FND-06：要求 API Key，且服务账号对该 Flow 有 USE。 */
    @PostMapping("/webhook/{versionId}")
    public ApiResponse<Map<String, Object>> webhook(@PathVariable String versionId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        Principal p = principals.current();
        if (!"api_key".equals(p.authMethod())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "webhook 需使用 API Key");
        }
        String draftId = publicationService.requireDraftId(versionId);
        resourceGuard.check("flow", draftId, AccessLevel.USE);
        return ApiResponse.ok(publicationService.invokeInternalApi(versionId, body));
    }
}
