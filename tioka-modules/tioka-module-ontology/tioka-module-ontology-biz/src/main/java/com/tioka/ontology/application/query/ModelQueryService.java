package com.tioka.ontology.application.query;

import com.tioka.framework.security.Principal;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.OntInstanceVersionRepository;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 本体实例读模型（ONT-03，M3-03 §8）：
 * 行过滤口径 = {@code acl_principals} 与调用主体标识交集；交集为空 → 空（NOT_FOUND，
 * 不泄露实例存在性——行过滤后语义，区别于 DatasetGateway 的「数据集不存在」404）。
 * 读草稿（{@code version=draft}）需额外权限 {@link #PERM_READ_DRAFT}（无权限同口径 NOT_FOUND）。
 *
 * <p>主体标识（经 on_behalf_of 交集的 PrincipalSnapshot，参考 DatasetPolicyModel.PrincipalSnapshot）：
 * {@code userId}、{@code onBehalfOf}（MAS-01 经办人）与角色编码的并集；
 * 实例 ACL 命中任一标识即可见（deny-by-default：ACL 为空不可见）。
 */
@Service
public class ModelQueryService {

    /** 读草稿的额外动作类别（M3-03 §8：只有建模与审查 Agent 的授权书里有）。 */
    public static final String PERM_READ_DRAFT = "ontology.read_draft";

    private final OntInstanceVersionRepository versions;
    private final OntDraftRepository drafts;

    public ModelQueryService(OntInstanceVersionRepository versions, OntDraftRepository drafts) {
        this.versions = versions;
        this.drafts = drafts;
    }

    /**
     * 读已确认实例（version 缺省取 findLatestConfirmed）。权限过滤不通过或不存在 → empty（NOT_FOUND 口径）。
     */
    public Optional<OntInstanceVersionDO> readConfirmed(String instanceKey, Integer version, Principal principal) {
        Optional<OntInstanceVersionDO> row = version == null
                ? versions.findLatestConfirmed(instanceKey)
                : versions.find(instanceKey, version).filter(r -> "CONFIRMED".equals(r.getStatus()));
        return row.filter(r -> visible(r, principal));
    }

    /** 读草稿；无 {@code ontology.read_draft} 权限 → empty（与无权实例同口径，不泄露存在性）。 */
    public Optional<OntDraftDO> readDraft(String instanceKey, Principal principal) {
        if (principal == null || !principal.hasPermission(PERM_READ_DRAFT)) {
            return Optional.empty();
        }
        return drafts.find(instanceKey);
    }

    /** 行谓词：{@code acl_principals ∩ 主体标识 ≠ ∅}。 */
    public boolean visible(OntInstanceVersionDO row, Principal principal) {
        if (principal == null) {
            return false;
        }
        Set<String> keys = principalKeys(principal);
        if (row.getAclPrincipals() == null || row.getAclPrincipals().isEmpty()) {
            return false;
        }
        return row.getAclPrincipals().stream().anyMatch(keys::contains);
    }

    /** 主体标识 = userId ∪ onBehalfOf ∪ roles（经 on_behalf_of 交集后的有效身份面）。 */
    public static Set<String> principalKeys(Principal principal) {
        Set<String> keys = new LinkedHashSet<>();
        if (principal.userId() != null && !principal.userId().isBlank()) {
            keys.add(principal.userId());
        }
        if (principal.onBehalfOf() != null && !principal.onBehalfOf().isBlank()) {
            keys.add(principal.onBehalfOf());
        }
        keys.addAll(principal.roles());
        return keys;
    }
}
