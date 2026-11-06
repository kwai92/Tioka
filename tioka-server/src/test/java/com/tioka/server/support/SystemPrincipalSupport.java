package com.tioka.server.support;

import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** IT 种子数据：以 SYSTEM 身份绕过 workspace 行级过滤。 */
@Component
public class SystemPrincipalSupport {
    @Autowired PrincipalHolder principals;

    public <T> T run(Supplier<T> body) {
        return principals.runAs(SystemPrincipals.system(), body);
    }

    public void run(Runnable body) {
        principals.runAs(SystemPrincipals.system(), body);
    }
}
