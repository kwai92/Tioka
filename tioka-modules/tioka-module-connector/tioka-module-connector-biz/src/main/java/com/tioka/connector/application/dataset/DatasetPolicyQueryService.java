package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.DatasetPolicyQueryApi;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import org.springframework.stereotype.Service;

/** {@link DatasetPolicyQueryApi} 实现（DAT-01）。 */
@Service
public class DatasetPolicyQueryService implements DatasetPolicyQueryApi {

    private final DatasetStore store;
    private final PrincipalHolder principals;

    public DatasetPolicyQueryService(DatasetStore store, PrincipalHolder principals) {
        this.store = store;
        this.principals = principals;
    }

    private String workspaceId() {
        return principals.find().map(Principal::workspaceId).orElse(null);
    }

    @Override
    public boolean exists(String name) {
        String ws = workspaceId();
        return ws != null && store.find(ws, name).isPresent();
    }

    @Override
    public boolean hasEnforcePolicy(String name) {
        String ws = workspaceId();
        if (ws == null) {
            return false;
        }
        return store.find(ws, name)
                .map(r -> "ENFORCE".equalsIgnoreCase(r.mode()))
                .orElse(false);
    }
}
