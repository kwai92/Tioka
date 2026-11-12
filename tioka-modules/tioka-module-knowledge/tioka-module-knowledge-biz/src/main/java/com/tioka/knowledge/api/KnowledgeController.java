package com.tioka.knowledge.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.knowledge.application.DataSourceService;
import com.tioka.knowledge.application.KnowledgeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/knowledge")
@RequirePermission(Permissions.KB_WRITE)
public class KnowledgeController {
    private final KnowledgeService service;
    private final DataSourceService dataSourceService;

    public KnowledgeController(KnowledgeService service, DataSourceService dataSourceService) {
        this.service = service;
        this.dataSourceService = dataSourceService;
    }

    @GetMapping("/embedding-bindings")
    public ApiResponse<List<KnowledgeService.EmbeddingModelBindingView>> listEmbeddingBindings() {
        return ApiResponse.ok(service.listEmbeddingBindings());
    }

    @PostMapping("/bases")
    @Audited(action = "knowledge.base.create", resourceType = "kb")
    public ApiResponse<KnowledgeService.KnowledgeBase> createBase(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.createBase(
                body.getOrDefault("workspaceId", "ws_default"),
                body.get("name"),
                body.get("embeddingBindingId")));
    }

    @GetMapping("/bases/{id}/reindex-progress")
    public ApiResponse<KnowledgeService.ReindexProgress> reindexProgress(@PathVariable String id) {
        return ApiResponse.ok(service.reindexProgress(id));
    }

    /** KNW-03：切块策略配置（chunk_policy_json + 上下文增强开关），修改后触发既有重建路径。 */
    @PutMapping("/bases/{id}/chunk-policy")
    @Audited(action = "knowledge.base.chunk_policy", resourceType = "kb", resourceIdArg = "#id")
    public ApiResponse<Map<String, Object>> updateChunkPolicy(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body) {
        String policyJson = body.get("chunkPolicy") == null ? null
                : String.valueOf(body.get("chunkPolicy"));
        Boolean contextEnhancement = body.get("contextEnhancement") == null ? null
                : Boolean.parseBoolean(String.valueOf(body.get("contextEnhancement")));
        int triggered = service.updateChunkPolicy(id, policyJson, contextEnhancement);
        return ApiResponse.ok(Map.of("triggeredReindex", triggered));
    }

    /** KNW-04 / V227：绑定 rerank 模型与阈值；bindingId 传 null 或空串即解绑（跳过且不设阈值）。 */
    @PutMapping("/bases/{id}/rerank")
    @Audited(action = "knowledge.base.rerank", resourceType = "kb", resourceIdArg = "#id")
    public ApiResponse<Map<String, Object>> configureRerank(@PathVariable String id,
                                                              @RequestBody Map<String, Object> body) {
        String bindingId = body.get("rerankBindingId") == null ? null
                : String.valueOf(body.get("rerankBindingId"));
        if (bindingId != null && bindingId.isBlank()) {
            bindingId = null;
        }
        Double threshold;
        try {
            threshold = body.get("rerankThreshold") == null ? null
                    : Double.parseDouble(String.valueOf(body.get("rerankThreshold")));
        } catch (NumberFormatException e) {
            // 非法数值必须返回 BAD_REQUEST，不能让 NumberFormatException 变成 500。
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST,
                    "rerankThreshold 须为 0〜1 之间的数值");
        }
        if (threshold != null && !Double.isFinite(threshold)) {
            // NaN / Infinity 会让区间校验恒 false 且比较恒 false，等于关掉阈值。
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.BAD_REQUEST,
                    "rerankThreshold 须为有限数值");
        }
        service.configureRerank(id, bindingId, threshold);
        return ApiResponse.ok(Map.of(
                "rerankBindingId", bindingId == null ? "" : bindingId,
                "rerankThreshold", threshold == null ? 0.3d : threshold));
    }

    @PostMapping("/bases/{id}/documents")
    @Audited(action = "knowledge.document.ingest", resourceType = "kb", resourceIdArg = "#id")
    public ApiResponse<KnowledgeService.Document> ingest(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(service.ingest(
                id,
                body.get("title"),
                body.getOrDefault("content", ""),
                body.getOrDefault("sourceType", "upload"),
                body.getOrDefault("format", "txt")));
    }

    @PostMapping("/bases/{id}/documents/upload")
    @Audited(action = "knowledge.document.upload", resourceType = "kb", resourceIdArg = "#id")
    public ApiResponse<KnowledgeService.Document> upload(@PathVariable String id,
                                                         @RequestParam("file") MultipartFile file,
                                                         @RequestParam(value = "title", required = false) String title)
            throws Exception {
        byte[] bytes = file.getBytes();
        String filename = file.getOriginalFilename();
        return ApiResponse.ok(service.ingestUpload(id, title, bytes, filename));
    }

    @PostMapping("/bases/{id}/search")
    public ApiResponse<List<KnowledgeService.RetrievalHit>> search(@PathVariable String id, @RequestBody Map<String, Object> body) {
        int topK = body.get("topK") == null ? 3 : Integer.parseInt(String.valueOf(body.get("topK")));
        return ApiResponse.ok(service.search(id, String.valueOf(body.get("query")), topK));
    }

    @GetMapping("/citations/{id}")
    public ApiResponse<KnowledgeService.Citation> citation(@PathVariable String id) {
        return ApiResponse.ok(service.resolveCitation(id));
    }

    @PostMapping("/datasources")
    @Audited(action = "knowledge.datasource.bind", resourceType = "kb_datasource")
    public ApiResponse<com.tioka.knowledge.api.DataSourceApi.DataSourceView> bindDatasource(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(dataSourceService.bind(
                body.get("kbId"),
                body.getOrDefault("type", "upload"),
                body.getOrDefault("uri", body.getOrDefault("url", ""))));
    }

    @PostMapping("/datasources/{id}/sync")
    @Audited(action = "knowledge.datasource.sync", resourceType = "kb_datasource", resourceIdArg = "#id")
    public ApiResponse<Map<String, String>> syncDatasource(@PathVariable String id,
                                                           @RequestBody(required = false) Map<String, String> body) {
        String docId = dataSourceService.sync(id, body == null ? Map.of() : body);
        return ApiResponse.ok(Map.of("docId", docId));
    }
}
