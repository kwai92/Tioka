package com.tioka.knowledge.application;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class GraphRagService {
    public List<Map<String, String>> query(String kbId, String entity) {
        return List.of(Map.of("kbId", kbId, "entity", entity, "relation", "MENTIONS", "neighbor", entity + "-related"));
    }
}
