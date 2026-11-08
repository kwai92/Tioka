package com.tioka.ontology.api.schema;

import java.util.Optional;

/**
 * 读 data item 的唯一入口（ADR-066 / SCH-07）。
 */
public interface DataItemReader {

    Optional<DataItemView> read(String itemId, String schemaRef);
}
