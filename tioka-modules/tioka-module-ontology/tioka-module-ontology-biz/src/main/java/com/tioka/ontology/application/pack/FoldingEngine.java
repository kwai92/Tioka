package com.tioka.ontology.application.pack;

import java.util.List;
import java.util.Map;

/**
 * 合同链折叠引擎：纯函数，结果与到达顺序无关。
 */
public interface FoldingEngine {

    Map<String, Object> fold(String storeKey, List<Map<String, Object>> confirmedChain);
}
