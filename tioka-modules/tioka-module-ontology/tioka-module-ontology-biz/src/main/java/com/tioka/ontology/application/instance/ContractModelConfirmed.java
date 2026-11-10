package com.tioka.ontology.application.instance;

import java.util.List;

/**
 * 业务系统确认事件（M3-03 §7 / docs/contracts/events/contract-model-confirmed-v1.json）。
 */
public record ContractModelConfirmed(
        String contractKey,
        int version,
        String contentHash,
        String confirmedBy,
        List<String> aclPrincipals) {

    public ContractModelConfirmed {
        if (aclPrincipals == null) {
            aclPrincipals = List.of();
        }
    }
}
