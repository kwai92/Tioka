package com.tioka.ontology.api.docparse;

/**
 * 表格单元格引用（M3-03 doc_unit.table_ref：{tableId,row,col}；合并单元格取左上角坐标）。
 */
public record ParsedTableRef(
        int tableId,
        int row,
        int col
) {}
