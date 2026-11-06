package com.tioka.framework.persistence;

/** 带所有者的资源：插入时可由 MetaObjectHandler 填充 ownerId。 */
public interface Owned {
    String getOwnerId();
    void setOwnerId(String ownerId);
}
