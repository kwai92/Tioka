package com.tioka.connector.api.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 连接器类型规格（M3-06 CON-SDK）。
 */
public record ConnectorSpec(
        String type,
        JsonNode configSchema,
        boolean listChanges,
        boolean fetchDocuments,
        boolean queryDataset,
        boolean sourceAcl
) {}
