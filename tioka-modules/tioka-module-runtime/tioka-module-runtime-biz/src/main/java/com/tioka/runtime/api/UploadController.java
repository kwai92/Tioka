package com.tioka.runtime.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.runtime.application.UploadService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private final UploadService uploads;

    public UploadController(UploadService uploads) {
        this.uploads = uploads;
    }

    @PostMapping("/presign")
    public ApiResponse<UploadService.PresignResult> presign(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(uploads.presign(
                body.getOrDefault("filename", "file.bin"),
                body.getOrDefault("contentType", "application/octet-stream"),
                body.get("sessionId")));
    }

    @PutMapping("/{uploadId}/content")
    public ApiResponse<Void> put(@PathVariable String uploadId, @RequestBody byte[] body) {
        uploads.putContent(uploadId, body);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{uploadId}/confirm")
    public ApiResponse<UploadService.ConfirmResult> confirm(@PathVariable String uploadId,
                                                            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(uploads.confirm(uploadId, body.get("sessionId")));
    }

    @PostMapping("/sessions/{sessionId}/publish")
    public ApiResponse<?> publish(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(uploads.publish(sessionId, body.get("logicalPath"), body.get("contentType")));
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
