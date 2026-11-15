package com.tioka.model.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.BaseDO;

/**
 * Prompt 模板身份（AST-07 / ADR-041 §1、§4；V237）。
 *
 * <p>只存身份与作用域；内容（分段正文 / 变量定义 / 用途 / 输出格式 / 建议别名 / 样例变量）存 {@code cfg_*}。
 * {@code currentVersionId} / {@code currentVersionNo} 为当前生效版本投影（GOV-12 指针）。
 */
@TableName("prm_template")
public class PromptTemplateDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;
    /** 稳定名称：sys.* / pack.{包}.{名} / 空间级 / 个人级。 */
    private String name;
    /** system | pack | workspace | personal。 */
    private String scope;
    /** 作用域归属：包名 / 空间 ID / 用户 ID；系统级为空。 */
    private String scopeRef;
    /** GOV-12（V237）：当前生效版本指针。 */
    private String currentVersionId;
    private Integer currentVersionNo;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getScopeRef() { return scopeRef; }
    public void setScopeRef(String scopeRef) { this.scopeRef = scopeRef; }
    public String getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(String currentVersionId) { this.currentVersionId = currentVersionId; }
    public Integer getCurrentVersionNo() { return currentVersionNo; }
    public void setCurrentVersionNo(Integer currentVersionNo) { this.currentVersionNo = currentVersionNo; }
}
