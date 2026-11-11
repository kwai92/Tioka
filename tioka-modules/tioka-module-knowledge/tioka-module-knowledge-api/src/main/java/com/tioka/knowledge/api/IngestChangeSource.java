package com.tioka.knowledge.api;

import java.util.List;

/**
 * 入库任务的 ChangeEvent 来源（约束 F39 / ADR-018 修订：aclPrincipals 可选）。
 * KNW-06 数据源连接器（M4 前段）只需产生本事件即可入库。
 *
 * @param externalId      源系统文档标识
 * @param kind            UPSERT / DELETE
 * @param externalVersion 源系统版本号，可空
 * @param title           文档标题
 * @param aclPrincipals   源权限主体，可选（有源权限时检索取知识库级与文档级权限交集）
 */
public record IngestChangeSource(
        String externalId,
        String kind,
        String externalVersion,
        String title,
        List<String> aclPrincipals) {
}
