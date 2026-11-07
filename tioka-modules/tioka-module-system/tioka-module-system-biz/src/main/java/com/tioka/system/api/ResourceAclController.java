package com.tioka.system.api;

import com.tioka.framework.error.ApiResponse;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.RequirePermission;
import com.tioka.system.api.authz.ResourceAclApi;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ResourceAclController {
    private final ResourceAclApi acls;

    public ResourceAclController(ResourceAclApi acls) {
        this.acls = acls;
    }

    @RequirePermission({})
    @GetMapping("/{type:agent|kb|flow|mcp|skill}/{id}/acl")
    public ApiResponse<List<ResourceAclApi.AclEntry>> list(@PathVariable String type,
                                                           @PathVariable String id) {
        return ApiResponse.ok(acls.list(type, id));
    }

    @RequirePermission({})
    @PutMapping("/{type:agent|kb|flow|mcp|skill}/{id}/acl")
    public ApiResponse<Void> replace(@PathVariable String type,
                                     @PathVariable String id,
                                     @RequestBody List<Map<String, String>> body) {
        List<ResourceAclApi.AclEntry> entries = new ArrayList<>();
        if (body != null) {
            for (Map<String, String> m : body) {
                entries.add(new ResourceAclApi.AclEntry(
                        m.get("subjectType"),
                        m.get("subjectId"),
                        m.get("subjectName"),
                        AccessLevel.valueOf(m.get("level"))));
            }
        }
        acls.replace(type, id, entries);
        return ApiResponse.ok(null);
    }
}
