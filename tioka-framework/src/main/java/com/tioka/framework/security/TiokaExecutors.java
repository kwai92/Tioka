package com.tioka.framework.security;

import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;

import java.util.concurrent.ExecutorService;

public final class TiokaExecutors {
    private TiokaExecutors() {}

    public static ExecutorService wrap(ExecutorService executor) {
        return new DelegatingSecurityContextExecutorService(executor);
    }
}
