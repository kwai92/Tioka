package com.tioka.knowledge.application;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EvalService {
    private final KnowledgeService knowledgeService;

    public EvalService(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 固定输出结构：kbId, queries, recallAtK, hits。
     */
    public Map<String, Object> run(String kbId, List<Map<String, String>> dataset) {
        return run(kbId, dataset, 3);
    }

    public Map<String, Object> run(String kbId, List<Map<String, String>> dataset, int k) {
        int topK = Math.max(1, k);
        List<Map<String, String>> rows = dataset == null ? List.of() : dataset;
        List<String> queries = new ArrayList<>();
        List<Map<String, Object>> hits = new ArrayList<>();
        int hitCount = 0;
        for (Map<String, String> row : rows) {
            String query = row.getOrDefault("query", "");
            queries.add(query);
            String expect = row.getOrDefault("expectContains", "");
            var results = knowledgeService.search(kbId, query, topK);
            boolean matched = results.stream().anyMatch(r -> r.text() != null && r.text().contains(expect));
            if (matched) hitCount++;
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("query", query);
            hit.put("expectContains", expect);
            hit.put("matched", matched);
            hit.put("results", results.stream()
                    .map(r -> Map.of(
                            "chunkId", r.chunkId(),
                            "citationId", r.citationId(),
                            "score", r.score(),
                            "text", r.text() == null ? "" : r.text()))
                    .toList());
            hits.add(hit);
        }
        double recallAtK = rows.isEmpty() ? 0.0 : (double) hitCount / rows.size();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kbId", kbId);
        report.put("queries", queries);
        report.put("recallAtK", recallAtK);
        report.put("hits", hits);
        // 兼容旧字段
        report.put("total", rows.size());
        report.put("hit", hitCount);
        report.put("recallAt3", topK == 3 ? recallAtK : report.get("recallAtK"));
        return report;
    }
}
