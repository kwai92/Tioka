package com.tioka.framework.security;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

@Component
public class SecurityContextPrincipalHolder implements PrincipalHolder {

    @Override
    public Principal current() {
        return find().orElseThrow(() -> new BizException(ErrorCode.UNAUTHORIZED, "未登录"));
    }

    @Override
    public Optional<Principal> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof TiokaAuthentication ta) {
            return Optional.of(ta.getPrincipal());
        }
        return Optional.empty();
    }

    @Override
    public <T> T runAs(Principal principal, Supplier<T> body) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new TiokaAuthentication(principal));
        SecurityContextHolder.setContext(ctx);
        try {
            return body.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
