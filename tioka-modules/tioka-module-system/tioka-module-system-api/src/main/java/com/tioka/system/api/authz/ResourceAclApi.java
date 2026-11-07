package com.tioka.system.api.authz;

import com.tioka.framework.security.AccessLevel;

import java.util.List;

/** 资源 ACL 读写（FND-04）；供各域创建资源时写入 OWNER。 */
public interface ResourceAclApi {

    void ensureOwner(String resourceType, String resourceId, String userId);

    List<AclEntry> list(String resourceType, String resourceId);

    void replace(String resourceType, String resourceId, List<AclEntry> entries);

    record AclEntry(String subjectType, String subjectId, String subjectName, AccessLevel level) {}
}
