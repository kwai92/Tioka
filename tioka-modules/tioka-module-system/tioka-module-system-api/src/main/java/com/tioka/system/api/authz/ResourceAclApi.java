package com.tioka.system.api.authz;

import com.tioka.framework.security.AccessLevel;

import java.util.List;

/** 资源 ACL 读写（FND-04）；供各域创建资源时写入 OWNER。 */
public interface ResourceAclApi {

    void ensureOwner(String resourceType, String resourceId, String userId);

    List<AclEntry> list(String resourceType, String resourceId);

    void replace(String resourceType, String resourceId, List<AclEntry> entries);

    /**
     * 仅查 ACL 行是否覆盖当前主体的要求等级；不经 ResourceLookup（供 service_account 等无资源目录的类型）。
     * TRU-01：SERVICE 名义下校验发布者对服务账号的 USE。
     */
    default boolean aclAllows(String resourceType, String resourceId, AccessLevel level) {
        return false;
    }

    record AclEntry(String subjectType, String subjectId, String subjectName, AccessLevel level) {}
}
