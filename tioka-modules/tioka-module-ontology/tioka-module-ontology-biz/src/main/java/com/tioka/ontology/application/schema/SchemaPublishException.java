package com.tioka.ontology.application.schema;

import com.tioka.ontology.api.schema.SchemaError;

import java.util.List;

public final class SchemaPublishException extends RuntimeException {

    private final List<SchemaError> errors;

    public SchemaPublishException(List<SchemaError> errors) {
        super(errors.isEmpty() ? "schema publish rejected" : errors.get(0).message());
        this.errors = List.copyOf(errors);
    }

    public List<SchemaError> errors() {
        return errors;
    }
}
