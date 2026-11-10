package com.tioka.connector.application.dataset;

import com.tioka.connector.api.dataset.ColumnSpec;
import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.PlannedQuery;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelConstant;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 行谓词与查询过滤编译器（DAT-01 / M3-02「CEL 子集与编译」）。
 *
 * <p>允许：列引用 {@code row.col}、principal 属性 {@code principal.attrs.x} / {@code principal.roles}、
 * 字面量、{@code == != < <= > >=}、{@code in}、{@code && || !}、{@code startsWith}。
 * 其余语法在编译期经 AST 遍历白名单拒绝（保存策略时调用 {@link #validate}）。
 *
 * <p>编译口径（M3-02 伪码）：
 * ① rows：when 对 eff 求值为真的规则，取其 predicate 的 OR；一条都不为真 → FALSE（零行）；
 * ② AND 用户 filter AND grant.scope 中与列同名的键（如 contractId in [...]）；
 * ③ principal.* 在编译期代入为常量（参数绑定），只剩 row.* 进入 SQL / REST；
 * ④ 列：q.columns ∩ 描述中的列；DROP 列从 select 去掉。
 *
 * <p>CEL 复用方式（偏差登记）：CelRuleCondition 在 orchestration-biz，biz→biz 不建立依赖，
 * connector-biz 自带 dev.cel（版本与 orchestration-biz 对齐，tioka-dependencies 统一管理）。
 */
@Component
public class DatasetPolicyCompiler {

    // cel-java 0.9.1 运算符函数名带下划线包裹（_==_ / _&&_ / !_ / @in），探针实证
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_", "@in", "_&&_", "_||_", "!_", "startsWith");
    private static final Set<String> ALLOWED_ROOTS = Set.of("row", "principal");

    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("row", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("principal", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .setResultType(SimpleType.BOOL)
            .build();
    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    /** AST 白名单校验：白名单外语法（函数调用、字符串拼接等）抛 BAD_REQUEST。 */
    public CelAbstractSyntaxTree validate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL 表达式为空");
        }
        CelValidationResult result = COMPILER.compile(expression);
        if (result.hasError()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL 编译失败: " + result.getErrorString());
        }
        CelAbstractSyntaxTree ast;
        try {
            ast = result.getAst();
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL AST 不可用: " + e.getMessage());
        }
        walk(ast.getExpr());
        return ast;
    }

    /** when（仅 principal）求值：CEL 运行期代入 principal 常量。 */
    public boolean evalWhen(String when, DatasetPolicyModel.PrincipalSnapshot eff) {
        if (when == null || when.isBlank()) {
            return true;
        }
        return evalAst(validate(when), Map.of(), eff.toCelActivation());
    }

    /**
     * 编译（M3-02 伪码）。
     *
     * @param restMode true = REST/插件路径（restParams + postFilter）；false = JDBC（whereSql + params）
     */
    public PlannedQuery compile(DatasetPolicyModel.DatasetPolicy policy,
                                DatasetPolicyModel.PrincipalSnapshot eff,
                                Map<String, List<String>> grantScope,
                                DatasetQuery query,
                                DatasetDescriptor descriptor,
                                boolean restMode) {
        // ④ 列选择：q.columns ∩ 描述列；DROP 列去掉
        Map<String, ColumnSpec> colSpecs = new LinkedHashMap<>();
        for (ColumnSpec c : descriptor.columns()) {
            colSpecs.put(c.name(), c);
        }
        Map<String, ColumnSpec.Mask> effectiveMasks = effectiveMasks(policy, eff, colSpecs);
        Set<String> select = new LinkedHashSet<>();
        if (query.columns() == null || query.columns().isEmpty()) {
            for (String name : colSpecs.keySet()) {
                classifyColumn(name, effectiveMasks, select);
            }
        } else {
            for (String c : query.columns()) {
                if (!colSpecs.containsKey(c)) {
                    throw new BizException(ErrorCode.BAD_REQUEST, "未知列: " + c);
                }
                classifyColumn(c, effectiveMasks, select);
            }
        }

        // ① rows OR + ② filter/scope AND（ast 列表供 REST postFilter 运行期求值）
        List<CelExpr> orPredicates = new ArrayList<>();
        List<CelAbstractSyntaxTree> andAsts = new ArrayList<>();
        boolean anyRule = false;
        for (DatasetPolicyModel.RowRule rule : policy.rows()) {
            if (evalWhen(rule.when(), eff)) {
                anyRule = true;
                CelAbstractSyntaxTree ast = validate(rule.predicate());
                orPredicates.add(ast.getExpr());
                andAsts.add(ast);
            }
        }
        if (!anyRule) {
            // 一条都不为真 → 零行（默认拒绝）
            return new PlannedQuery("1 = 0", List.of(), Map.of(), row -> false,
                    List.copyOf(select), false, effectiveMasks);
        }

        if (query.filter() != null && !query.filter().isBlank()) {
            CelAbstractSyntaxTree ast = validate(query.filter());
            orPredicates.add(ast.getExpr());
            andAsts.add(ast);
        }
        if (grantScope != null) {
            for (Map.Entry<String, List<String>> e : grantScope.entrySet()) {
                if (colSpecs.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                    CelAbstractSyntaxTree ast = inConstExpr(e.getKey(), e.getValue());
                    orPredicates.add(ast.getExpr());
                    andAsts.add(ast);
                }
            }
        }

        Map<String, Object> principalActivation = eff.toCelActivation();

        if (restMode) {
            Map<String, String> restParams = new LinkedHashMap<>();
            if (query.filter() != null && !query.filter().isBlank()) {
                collectPushable(validate(query.filter()).getExpr(), colSpecs, restParams);
            }
            for (Map.Entry<String, List<String>> e : (grantScope == null
                    ? Map.<String, List<String>>of() : grantScope).entrySet()) {
                if (colSpecs.containsKey(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
                    restParams.put(e.getKey(), String.join(",",
                            e.getValue().stream().map(String::valueOf).toList()));
                }
            }
            Predicate<Map<String, Object>> postFilter = row -> {
                for (CelAbstractSyntaxTree ast : andAsts) {
                    if (!evalAst(ast, row, principalActivation)) {
                        return false;
                    }
                }
                return true;
            };
            return new PlannedQuery(null, List.of(), restParams, postFilter,
                    List.copyOf(select), !restParams.isEmpty(), effectiveMasks);
        }

        // JDBC：谓词 → 外层 WHERE 片段（参数化）
        DatasetPolicyModel.SqlFragment where = null;
        for (CelExpr expr : orPredicates) {
            DatasetPolicyModel.SqlFragment f = toSql(expr, colSpecs, principalActivation);
            where = where == null ? f : and(where, f);
        }
        return new PlannedQuery(where == null ? null : where.sql(),
                where == null ? List.of() : where.params(),
                Map.of(),
                null,
                List.copyOf(select),
                false,
                effectiveMasks);
    }

    private void classifyColumn(String name, Map<String, ColumnSpec.Mask> masks,
                                Set<String> select) {
        if (masks.getOrDefault(name, ColumnSpec.Mask.NONE) != ColumnSpec.Mask.DROP) {
            select.add(name);
        }
    }

    /** 列掩码解析：命中的 ColumnRule 取其 mask（后规则覆盖），未命中按列密级缺省。 */
    Map<String, ColumnSpec.Mask> effectiveMasks(DatasetPolicyModel.DatasetPolicy policy,
                                                DatasetPolicyModel.PrincipalSnapshot eff,
                                                Map<String, ColumnSpec> colSpecs) {
        Map<String, ColumnSpec.Mask> out = new HashMap<>();
        for (String name : colSpecs.keySet()) {
            out.put(name, colSpecs.get(name).defaultMask());
        }
        for (DatasetPolicyModel.ColumnRule rule : policy.columns()) {
            if (!colSpecs.containsKey(rule.column())) {
                throw new BizException(ErrorCode.BAD_REQUEST, "策略引用未知列: " + rule.column());
            }
            if (evalWhen(rule.when(), eff) && rule.mask() != null) {
                out.put(rule.column(), rule.mask());
            }
        }
        return Map.copyOf(out);
    }

    /** grant.scope 中与列同名的键 → 常量 IN（值是 scope 键集合，单引号转义后编译）。 */
    private CelAbstractSyntaxTree inConstExpr(String column, List<String> values) {
        StringBuilder sb = new StringBuilder("row.").append(column).append(" in [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(String.valueOf(values.get(i)).replace("'", "\\'")).append('\'');
        }
        sb.append(']');
        return validate(sb.toString());
    }

    private void collectPushable(CelExpr expr, Map<String, ColumnSpec> colSpecs,
                                 Map<String, String> restParams) {
        if (expr.getKind() != CelExpr.ExprKind.Kind.CALL) {
            return;
        }
        CelExpr.CelCall call = expr.call();
        String fn = call.function();
        if ("_&&_".equals(fn) || "_||_".equals(fn)) {
            for (CelExpr arg : call.args()) {
                collectPushable(arg, colSpecs, restParams);
            }
            return;
        }
        if ("_==_".equals(fn) && call.args().size() == 2) {
            String col = rowColumnOf(call.args().get(0), colSpecs);
            CelExpr rhs = call.args().get(1);
            if (col != null && rhs.getKind() == CelExpr.ExprKind.Kind.CONSTANT) {
                restParams.put(col, String.valueOf(constantValue(rhs.constant())));
            }
        }
    }

    // ---------------- SQL 生成（参数化，零字符串拼接值） ----------------

    private DatasetPolicyModel.SqlFragment toSql(CelExpr expr,
                                                 Map<String, ColumnSpec> colSpecs,
                                                 Map<String, Object> principal) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();
        emit(expr, sql, params, colSpecs, principal);
        return new DatasetPolicyModel.SqlFragment(sql.toString(), params);
    }

    private DatasetPolicyModel.SqlFragment and(DatasetPolicyModel.SqlFragment a,
                                               DatasetPolicyModel.SqlFragment b) {
        List<Object> params = new ArrayList<>(a.params());
        params.addAll(b.params());
        return new DatasetPolicyModel.SqlFragment("(" + a.sql() + " AND " + b.sql() + ")", params);
    }

    private void emit(CelExpr expr, StringBuilder sql, List<Object> params,
                      Map<String, ColumnSpec> colSpecs, Map<String, Object> principal) {
        switch (expr.getKind()) {
            case CONSTANT -> {
                params.add(constantValue(expr.constant()));
                sql.append("?");
            }
            case IDENT -> throw reject("裸标识符须带 row./principal. 前缀");
            case SELECT -> sql.append(columnOrParam(expr, colSpecs, principal, params));
            case CALL -> emitCall(expr, sql, params, colSpecs, principal);
            case LIST -> {
                sql.append("(");
                for (int i = 0; i < expr.list().elements().size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    CelExpr e = expr.list().elements().get(i);
                    if (e.getKind() != CelExpr.ExprKind.Kind.CONSTANT) {
                        throw reject("IN 列表元素须为字面量");
                    }
                    params.add(constantValue(e.constant()));
                    sql.append("?");
                }
                sql.append(")");
            }
            default -> throw reject("CEL 子集白名单外语法: " + expr.getKind());
        }
    }

    private void emitCall(CelExpr expr, StringBuilder sql, List<Object> params,
                          Map<String, ColumnSpec> colSpecs, Map<String, Object> principal) {
        CelExpr.CelCall call = expr.call();
        String fn = call.function();
        if (!ALLOWED_FUNCTIONS.contains(fn)) {
            throw reject("CEL 子集白名单外函数: " + fn);
        }
        List<CelExpr> args = new ArrayList<>();
        if (call.target().isPresent()) {
            args.add(call.target().get());
        }
        args.addAll(call.args());
        switch (fn) {
            case "_&&_", "_||_" -> {
                requireArgs(args, 2, fn);
                sql.append("(");
                emit(args.get(0), sql, params, colSpecs, principal);
                sql.append(fn.equals("_&&_") ? " AND " : " OR ");
                emit(args.get(1), sql, params, colSpecs, principal);
                sql.append(")");
            }
            case "!_" -> {
                requireArgs(args, 1, fn);
                sql.append("(NOT ");
                emit(args.get(0), sql, params, colSpecs, principal);
                sql.append(")");
            }
            case "@in" -> {
                requireArgs(args, 2, fn);
                emit(args.get(0), sql, params, colSpecs, principal);
                sql.append(" IN ");
                emit(args.get(1), sql, params, colSpecs, principal);
            }
            case "startsWith" -> {
                requireArgs(args, 2, fn);
                emit(args.get(0), sql, params, colSpecs, principal);
                sql.append(" LIKE ");
                emitConstPrefix(args.get(1), sql, params);
            }
            case "_==_", "_!=_", "_<_", "_<=_", "_>_", "_>=_" -> {
                requireArgs(args, 2, fn);
                emit(args.get(0), sql, params, colSpecs, principal);
                sql.append(switch (fn) {
                    case "_==_" -> " = ";
                    case "_!=_" -> " <> ";
                    // _<_ / _<=_ / _>_ / _>=_ 去掉两侧下划线即 SQL 运算符
                    default -> " " + fn.substring(1, fn.length() - 1) + " ";
                });
                emit(args.get(1), sql, params, colSpecs, principal);
            }
            default -> throw reject("CEL 子集白名单外函数: " + fn);
        }
    }

    private static void requireArgs(List<CelExpr> args, int n, String fn) {
        if (args.size() != n) {
            throw reject("函数参数数量错误: " + fn);
        }
    }

    /** startsWith(row.col, "x") → row.col LIKE ?（值转义 + % 后缀，参数绑定）。 */
    private void emitConstPrefix(CelExpr arg, StringBuilder sql, List<Object> params) {
        if (arg.getKind() != CelExpr.ExprKind.Kind.CONSTANT
                || arg.constant().getKind() != CelConstant.Kind.STRING_VALUE) {
            throw reject("startsWith 右侧须为字符串字面量");
        }
        String prefix = arg.constant().stringValue()
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        params.add(prefix + "%");
        sql.append("?");
    }

    /** SELECT 链：row.col → t."col"；principal.* → 常量参数（编译期代入）。 */
    private String columnOrParam(CelExpr expr, Map<String, ColumnSpec> colSpecs,
                                 Map<String, Object> principal, List<Object> params) {
        String column = rowColumnOf(expr, colSpecs);
        if (column != null) {
            return "t.\"" + column + "\"";
        }
        params.add(evalPrincipalPath(expr, principal));
        return "?";
    }

    /** expr 是 row.* 列引用时返回列名（校验在描述列内），否则 null。 */
    private String rowColumnOf(CelExpr expr, Map<String, ColumnSpec> colSpecs) {
        if (expr.getKind() != CelExpr.ExprKind.Kind.SELECT) {
            return null;
        }
        CelExpr operand = expr.select().operand();
        if (operand.getKind() != CelExpr.ExprKind.Kind.IDENT
                || !"row".equals(operand.ident().name())) {
            return null;
        }
        String col = expr.select().field();
        if (!colSpecs.containsKey(col)) {
            throw reject("引用未知列: " + col);
        }
        return col;
    }

    /** principal.attrs.x / principal.roles 编译期代入。 */
    private Object evalPrincipalPath(CelExpr expr, Map<String, Object> principal) {
        List<String> path = new ArrayList<>();
        CelExpr cur = expr;
        while (cur.getKind() == CelExpr.ExprKind.Kind.SELECT) {
            path.add(0, cur.select().field());
            cur = cur.select().operand();
        }
        if (cur.getKind() != CelExpr.ExprKind.Kind.IDENT
                || !"principal".equals(cur.ident().name())) {
            throw reject("仅允许 row.* / principal.* 引用");
        }
        Object node = principal;
        for (String seg : path) {
            if (!(node instanceof Map<?, ?> m)) {
                return null;
            }
            node = m.get(seg);
        }
        return node;
    }

    private boolean evalAst(CelAbstractSyntaxTree ast, Map<String, Object> row,
                            Map<String, Object> principal) {
        CelRuntime.Program program;
        try {
            // runtime 只接受 type-checked AST（validate 返回的即 checked）
            program = RUNTIME.createProgram(ast);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL 程序构建失败: " + e.getMessage());
        }
        Object out;
        try {
            out = program.eval(Map.of("row", row, "principal", principal));
        } catch (CelEvaluationException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL 求值失败: " + e.getMessage());
        }
        if (!(out instanceof Boolean b)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL 表达式结果须为布尔");
        }
        return b;
    }

    private static BizException reject(String msg) {
        return new BizException(ErrorCode.BAD_REQUEST, msg);
    }

    private static Object constantValue(CelConstant c) {
        return switch (c.getKind()) {
            case STRING_VALUE -> c.stringValue();
            case INT64_VALUE -> c.int64Value();
            case UINT64_VALUE -> c.uint64Value();
            case DOUBLE_VALUE -> c.doubleValue();
            case BOOLEAN_VALUE -> c.booleanValue();
            case NULL_VALUE -> null;
            default -> throw reject("不支持的字面量类型: " + c.getKind());
        };
    }

    /** 白名单 AST 遍历（validate 用）：只允许比较 / in / 逻辑 / startsWith / 选择链 / 常量 / 列表。 */
    private void walk(CelExpr expr) {
        switch (expr.getKind()) {
            case CONSTANT -> { /* 任意字面量 */ }
            case IDENT -> {
                if (!ALLOWED_ROOTS.contains(expr.ident().name())) {
                    throw reject("不允许的标识符: " + expr.ident().name());
                }
            }
            case SELECT -> walk(expr.select().operand());
            case LIST -> expr.list().elements().forEach(this::walk);
            case CALL -> {
                String fn = expr.call().function();
                if (!ALLOWED_FUNCTIONS.contains(fn)) {
                    throw reject("CEL 子集白名单外函数: " + fn);
                }
                expr.call().target().ifPresent(this::walk);
                expr.call().args().forEach(this::walk);
            }
            default -> throw reject("CEL 子集白名单外语法: " + expr.getKind());
        }
    }
}
