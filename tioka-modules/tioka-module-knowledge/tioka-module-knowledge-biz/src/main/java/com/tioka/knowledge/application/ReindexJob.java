package com.tioka.knowledge.application;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * KNW-01：V119 后将 REINDEX_PENDING 文档经 ModelGateway 重嵌入。
 */
@Component
public class ReindexJob {
    private static final Logger log = LoggerFactory.getLogger(ReindexJob.class);

    private final KnowledgeRepository repo;
    private final KnowledgeService knowledge;
    private final PrincipalHolder principals;
    private final int batchSize;
    private final boolean enabled;

    public ReindexJob(KnowledgeRepository repo,
                      KnowledgeService knowledge,
                      PrincipalHolder principals,
                      @Value("${tioka.knowledge.reindex-batch-size:5}") int batchSize,
                      @Value("${tioka.knowledge.reindex-enabled:true}") boolean enabled) {
        this.repo = repo;
        this.knowledge = knowledge;
        this.principals = principals;
        this.batchSize = Math.max(1, batchSize);
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${tioka.knowledge.reindex-interval-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        List<KbDocumentDO> pending = repo.listDocumentsByStatus("REINDEX_PENDING", batchSize);
        if (pending.isEmpty()) {
            return;
        }
        principals.runAs(SystemPrincipals.system(), () -> {
            for (KbDocumentDO doc : pending) {
                try {
                    knowledge.reindexDocument(doc.getId());
                } catch (Exception e) {
                    log.warn("[knowledge.reindex] doc={} failed: {}", doc.getId(), e.getMessage());
                    KbDocumentDO latest = repo.findDocument(doc.getId());
                    if (latest != null) {
                        latest.setStatus("FAILED");
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        latest.setErrorMessage(msg.substring(0, Math.min(msg.length(), 2000)));
                        repo.updateDocument(latest);
                    }
                }
            }
            return null;
        });
    }
}
