package com.tioka.ontology.application.instance;

import com.tioka.ontology.domain.ModelPathDiff;
import java.util.List;

/**
 * 路径级差异服务（ONT-02，M3-03 §7）：{@code [{path, op: ADD/REMOVE/CHANGE, from, to}]}，
 * 数组按 {@code x-tioka-identity} 对齐（顺序变化不算差异）。算法在 domain 层
 * {@link ModelPathDiff}（纯逻辑），本类是应用层入口。
 */
public class DiffService {

    public List<ModelPathDiff.Entry> diff(com.fasterxml.jackson.databind.JsonNode from,
                                          com.fasterxml.jackson.databind.JsonNode to) {
        return ModelPathDiff.diff(from, to);
    }
}
