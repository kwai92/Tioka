package com.tioka.ontology.api.def;

/**
 * 路径目录叶子类型（M3-03 §6）：金额 / 计量 / 比率 / 面积 / 期间 / 枚举 / 文本；
 * OBJECT / ARRAY 仅用于属性结构展开，不进入目录叶子。
 */
public enum PathValueKind {
    AMOUNT,
    MEASURE,
    RATIO,
    AREA,
    PERIOD,
    ENUM,
    TEXT,
    OBJECT,
    ARRAY
}
