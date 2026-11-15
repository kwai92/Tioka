package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.Permissions;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.evidence.EvidencePackSigner;
import com.tioka.system.application.evidence.EvidencePackService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 证据包导出与平台公钥下发（OBS-12 / M4-06 §7 / ADR-075 §5）。
 *
 * <p>导出受 {@code audit:export}（+ {@code audit:read}）控制，导出本身写审计
 * {@code timeline.evidence.export}；包内 CONFIDENTIAL 内容按导出人权限决定是否包含。平台公钥可独立
 * 下发，供法务 / 审计方离线重算各文件哈希、包整体哈希并验签。
 */
@RestController
@RequestMapping("/api/system/evidence-pack")
public class EvidencePackController {

    private static final String HEADER_PACK_ID = "X-Pack-Id";
    private static final String HEADER_PACK_SHA256 = "X-Pack-Sha256";

    private final EvidencePackService service;
    private final EvidencePackSigner signer;

    public EvidencePackController(EvidencePackService service, EvidencePackSigner signer) {
        this.service = service;
        this.signer = signer;
    }

    /** 平台公钥（供离线验签）。 */
    public record PublicKeyView(String algorithm, String keyId, String publicKeyPem) {}

    /**
     * 导出证据包：对 {@code scopeType} + {@code scopeRef}（REQUEST=request_id，SUBJECT=subjectType:subjectId）
     * 装配时间线、审计、外部交互、运行清单、审批、配置版本与封印校验，并对包整体哈希签名。
     *
     * <p>{@code configObject} 可重复，形如 {@code objectType:objectId}，用于纳入「相关配置版本」。
     */
    @RequirePermission({Permissions.AUDIT_READ, Permissions.AUDIT_EXPORT})
    @GetMapping("/export")
    public void export(
            @RequestParam String scopeType,
            @RequestParam String scopeRef,
            @RequestParam(required = false) String workspaceId,
            @RequestParam(required = false) List<String> configObject,
            HttpServletResponse response) throws IOException {
        EvidencePackService.ExportResult result = service.export(
                new EvidencePackService.ExportRequest(scopeType, scopeRef, workspaceId, configObject));
        byte[] bytes = result.content();
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.setContentLength(bytes.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"evidence-pack-" + result.packId() + ".json\"");
        response.setHeader(HEADER_PACK_ID, result.packId());
        response.setHeader(HEADER_PACK_SHA256, result.packageHash());
        response.getOutputStream().write(bytes);
        response.getOutputStream().flush();
    }

    /** 平台公钥（离线验签用）：算法、密钥标识与 X.509 PEM。 */
    @RequirePermission(Permissions.AUDIT_READ)
    @GetMapping("/public-key")
    public ApiResponse<PublicKeyView> publicKey() {
        return ApiResponse.ok(new PublicKeyView(signer.algorithm(), signer.keyId(), signer.publicKeyPem()));
    }
}
