package com.tioka.ontology.api.doc;

import java.util.List;
import java.util.Optional;

/**
 * 文档层 API（M3-03 DOC-01）。ingest 异步，返回 docId。
 */
public interface DocumentApi {

    String ingest(FileRef fileRef);

    Optional<DocumentView> get(String docId);

    List<DocUnit> units(String docId, int fromSeq, int toSeq);

    List<DocClause> clauses(String docId);
}
