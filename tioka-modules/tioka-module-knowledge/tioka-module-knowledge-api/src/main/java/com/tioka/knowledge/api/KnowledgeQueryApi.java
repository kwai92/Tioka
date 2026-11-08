package com.tioka.knowledge.api;

/** 知识库存在性查询（AST-02 发布校验）。 */
public interface KnowledgeQueryApi {

    /** 对 kb_base 加 {@code FOR SHARE}；存在且未删除返回 true。 */
    boolean lockExists(String kbId);
}
