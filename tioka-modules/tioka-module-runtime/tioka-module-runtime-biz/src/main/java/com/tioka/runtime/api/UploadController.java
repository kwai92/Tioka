package com.tioka.runtime.api;

import com.tioka.system.api.audit.Audited;

import com.tioka.framework.error.ApiResponse;
import com.tioka.runtime.application.UploadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.tioka.framework.security.RequirePermission;
import com.tioka.framework.security.Permissions;

@RestController
@RequestMapping("/api/uploads")
@RequirePermission(Permissions.CHAT_USE)
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/presign")
    @Audited(action = "upload.presign", resourceType = "upload")
    public ApiResponse<UploadService.PresignResult> presign(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(uploads.presign(
                body.getOrDefault("filename", "file.bin"),
                body.getOrDefault("contentType", "application/octet-stream"),
                body.get("threadId")));
    }

    @PutMapping("/{uploadId}/content")
    @Audited(action = "upload.content.put", resourceType = "upload", resourceIdArg = "#uploadId")
    public ApiResponse<Void> put(@PathVariable String uploadId, @RequestBody byte[] body) {
        uploads.putContent(uploadId, body);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{uploadId}/confirm")
    @Audited(action = "upload.confirm", resourceType = "upload", resourceIdArg = "#uploadId")
    public ApiResponse<UploadService.ConfirmResult> confirm(@PathVariable String uploadId,
                                                            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(uploads.confirm(uploadId, body.get("threadId")));
    }

    @PostMapping("/sessions/{threadId}/publish")
    @Audited(action = "upload.session.publish", resourceType = "upload_session", resourceIdArg = "#threadId")
    public ApiResponse<?> publish(@PathVariable String threadId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(uploads.publish(threadId, body.get("logicalPath"), body.get("contentType")));
    }

    @GetMapping("/tickets/{ticket}")
    public ResponseEntity<byte[]> ticket(@PathVariable String ticket) {
        byte[] data = uploads.downloadByTicket(ticket);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
