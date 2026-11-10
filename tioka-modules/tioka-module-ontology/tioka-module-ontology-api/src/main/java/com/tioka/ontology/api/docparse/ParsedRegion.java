package com.tioka.ontology.api.docparse;

import java.util.List;

/**
 * 契约 v1 坐标区域（ADR-060 §3.3 regions）：页码 + 页内 0〜1 归一化 [x0,y0,x1,y1]。
 * 跨页块可有多项；Word 块 M3 无坐标。
 */
public record ParsedRegion(
        int pageNo,
        double x0,
        double y0,
        double x1,
        double y1
) {
    /** 两区域交并比（IoU）；无重叠或退化返回 0。 */
    public double iou(ParsedRegion other) {
        if (other == null) {
            return 0d;
        }
        double ix = Math.min(x1, other.x1) - Math.max(x0, other.x0);
        double iy = Math.min(y1, other.y1) - Math.max(y0, other.y0);
        if (ix <= 0 || iy <= 0) {
            return 0d;
        }
        double inter = ix * iy;
        double a = (x1 - x0) * (y1 - y0);
        double b = (other.x1 - other.x0) * (other.y1 - other.y0);
        double union = a + b - inter;
        return union <= 0 ? 0d : inter / union;
    }

    public List<Double> asList() {
        return List.of(x0, y0, x1, y1);
    }
}
