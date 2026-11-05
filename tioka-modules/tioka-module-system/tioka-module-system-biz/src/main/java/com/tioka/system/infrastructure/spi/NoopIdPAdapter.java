package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.IdPAdapter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class NoopIdPAdapter implements IdPAdapter {
    @Override public String name() { return "noop"; }
    @Override public Optional<FederatedIdentity> authenticate(Map<String, String> credentials) {
        return Optional.empty();
    }
}
