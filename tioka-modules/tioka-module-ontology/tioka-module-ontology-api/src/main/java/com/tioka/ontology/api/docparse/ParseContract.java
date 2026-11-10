package com.tioka.ontology.api.docparse;

/**
 * 解析输出契约 {@code tioka.parse/v1}（ADR-060 §3 / §3.6 M3 核心子集，PRS-00）。
 * 契约 v1 内只增不改：只能增加可选字段与新的 type/flags 取值（ADR-060 §3.5）。
 */
public final class ParseContract {

    /** 契约标识，固定为 {@code tioka.parse/v1}。 */
    public static final String CONTRACT = "tioka.parse/v1";

    // ---- 块类型（ADR-060 §3.3；M3 至少产出 HEADING/PARAGRAPH/LIST_ITEM/TABLE/TABLE_CELL/
    //      BLANK_FIELD/SEAL/PAGE_HEADER/PAGE_FOOTER，其余取值 M4 补齐） ----
    public static final String T_HEADING = "HEADING";
    public static final String T_PARAGRAPH = "PARAGRAPH";
    public static final String T_LIST_ITEM = "LIST_ITEM";
    public static final String T_TABLE = "TABLE";
    public static final String T_TABLE_CELL = "TABLE_CELL";
    public static final String T_BLANK_FIELD = "BLANK_FIELD";
    public static final String T_SEAL = "SEAL";
    public static final String T_SIGNATURE = "SIGNATURE";
    public static final String T_COMMENT = "COMMENT";
    public static final String T_PAGE_HEADER = "PAGE_HEADER";
    public static final String T_PAGE_FOOTER = "PAGE_FOOTER";

    // ---- 角色 ----
    public static final String R_BODY = "BODY";
    public static final String R_FURNITURE = "FURNITURE";
    public static final String R_ANNOTATION = "ANNOTATION";

    // ---- 文字来源 ----
    public static final String S_NATIVE = "NATIVE";
    public static final String S_TEXT_LAYER = "TEXT_LAYER";
    public static final String S_OCR = "OCR";
    public static final String S_MERGED = "MERGED";

    // ---- 页类型（ADR-060 §3.2） ----
    public static final String K_NATIVE = "NATIVE";
    public static final String K_TEXT_PDF = "TEXT_PDF";
    public static final String K_SCANNED = "SCANNED";
    public static final String K_MIXED = "MIXED";
    public static final String K_IMAGE = "IMAGE";

    // ---- 文档级状态 ----
    public static final String ST_READY = "READY";
    public static final String ST_PARTIAL = "PARTIAL";
    public static final String ST_FAILED = "FAILED";

    // ---- flags（M3 核心子集：LOW_CONFIDENCE / REVISED） ----
    public static final String F_LOW_CONFIDENCE = "LOW_CONFIDENCE";
    public static final String F_REVISED = "REVISED";

    private ParseContract() {
    }
}
