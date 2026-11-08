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
