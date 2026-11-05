package com.tioka.knowledge.api;

import java.util.List;
import java.util.Map;

/** Flow / 跨模块检索入口（避免 biz→biz）。 */
public interface KnowledgeSearchApi {
    List<Hit> searchKnowledge(String kbId, String query, int topK);

    record Hit(String docId, String chunkId, String text, double score, String title, String citationId) {
        public Map<String, Object> toMap() {
            return Map.of(
                    "docId", docId == null ? "" : docId,
                    "chunkId", chunkId == null ? "" : chunkId,
                    "text", text == null ? "" : text,
                    "score", score,
                    "title", title == null ? "" : title,
                    "citationId", citationId == null ? "" : citationId
            );
        }
    }
}
