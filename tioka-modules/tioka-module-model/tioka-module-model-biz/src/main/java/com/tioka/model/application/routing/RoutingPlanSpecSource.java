package com.tioka.model.application.routing;

import java.util.Optional;

/**
 * 路由计划 spec 读取口：优先 GOV-12 当前生效版本（cfg_version 投影，MH-08 / ADR-041 §2），
 * 版本框架未装配或无版本时回退投影行平铺 spec（V1 兼容）。
 */
public interface RoutingPlanSpecSource {

    Optional<RoutingPlanSnapshot> load(String planId);
}
