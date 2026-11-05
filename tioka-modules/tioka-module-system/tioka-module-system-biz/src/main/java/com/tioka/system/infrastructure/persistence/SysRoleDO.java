package com.tioka.system.infrastructure.persistence;

/** sys_role 只读投影（自定义 SQL；V100 TIMESTAMPTZ 不映射 BaseDO）。 */
public class SysRoleDO {
    private String code;
    private String name;
    private String scope;
    private Boolean builtin;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public Boolean getBuiltin() { return builtin; }
    public void setBuiltin(Boolean builtin) { this.builtin = builtin; }
}
