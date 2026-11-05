package com.tioka.system.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.system.infrastructure.ScimUserRepository;
import com.tioka.system.infrastructure.persistence.ScimUserDO;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScimSyncService {
    private final ScimUserRepository users;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScimSyncService(ScimUserRepository users) {
        this.users = users;
    }

    public Map<String, Object> sync(List<Map<String, String>> delta) {
        int upsert = 0;
        int removed = 0;
        if (delta == null) {
            return Map.of("upserted", 0, "removed", 0, "total", users.count());
        }
        for (Map<String, String> u : delta) {
            if (u == null) continue;
            String id = firstNonBlank(u.get("externalId"), u.get("id"), u.get("userName"));
            if (id == null) continue;
            String action = u.getOrDefault("action", "upsert");
            if ("delete".equalsIgnoreCase(action) || "remove".equalsIgnoreCase(action)) {
                if (users.softDelete(id)) removed++;
            } else {
                Map<String, String> copy = new HashMap<>(u);
                copy.put("id", id);
                try {
                    ScimUserDO row = new ScimUserDO();
                    row.setId(id);
                    row.setAttrsJson(mapper.writeValueAsString(copy));
                    users.upsert(row);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                upsert++;
            }
        }
        return Map.of("upserted", upsert, "removed", removed, "total", users.count());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
