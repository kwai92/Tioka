package com.tioka.ontology.application.pack;

import com.tioka.ontology.api.doc.DocumentView;

import java.util.List;
import java.util.Map;

/**
 * 领域校验器：纯函数，只读入参。
 */
public interface ModelValidator {

    String validatorId();

    List<Violation> validate(Map<String, Object> draft, DocumentView docs);
}
