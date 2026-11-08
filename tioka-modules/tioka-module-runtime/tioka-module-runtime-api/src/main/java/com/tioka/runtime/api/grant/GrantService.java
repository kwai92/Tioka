package com.tioka.runtime.api.grant;

import com.tioka.framework.security.Principal;

/**
 * 委派授予服务（ADR-039 / M3-01 MAS-01）。只建接口；实现在 runtime-biz。
 */
public interface GrantService {

    DelegationGrant root(Principal runAs, String onBehalfOf, GrantAgentView agent, MandateScope mandate);

    /** 结果不是父授予子集时抛业务异常 GRANT_NOT_NARROWER。 */
    DelegationGrant narrow(DelegationGrant parent, GrantAgentView child, TaskNarrow narrow);

    void reap(String grantId);

    DelegationGrant get(String grantId);
}
