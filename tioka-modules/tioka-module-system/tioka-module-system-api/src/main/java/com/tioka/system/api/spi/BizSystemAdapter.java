package com.tioka.system.api.spi;

import java.util.Map;

/** 客户业务系统适配器。 */
public interface BizSystemAdapter {
    String systemType();
    Map<String, Object> invoke(String action, Map<String, Object> payload);
}
