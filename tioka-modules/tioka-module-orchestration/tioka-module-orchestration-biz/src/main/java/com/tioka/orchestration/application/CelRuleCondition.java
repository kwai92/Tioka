package com.tioka.orchestration.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelIssue;
import dev.cel.common.CelSourceLocation;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * CEL 规则条件：仅暴露 {@code event} / {@code attrs}，禁止访问 body；求值超时 50ms。
 */
@Component
public final class CelRuleCondition {
    private static final Logger log = LoggerFactory.getLogger(CelRuleCondition.class);

    static final long EVAL_TIMEOUT_MS = 50L;
    private static final Pattern BODY_ACCESS = Pattern.compile("\\bevent\\.body\\b");

    private static final CelCompiler COMPILER = CelCompilerFactory.standardCelCompilerBuilder()
            .addVar("event", MapType.create(SimpleType.STRING, SimpleType.DYN))
            .addVar("attrs", MapType.create(SimpleType.STRING, SimpleType.STRING))
            .setResultType(SimpleType.BOOL)
            .build();

    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    private final ConcurrentHashMap<String, CelRuntime.Program> cache = new ConcurrentHashMap<>();
    private final ExecutorService evalPool;
    private final long evalTimeoutMs;

    public CelRuleCondition() {
        this(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "orch-cel-eval");
            t.setDaemon(true);
            return t;
        }), EVAL_TIMEOUT_MS);
    }

    CelRuleCondition(ExecutorService evalPool) {
        this(evalPool, EVAL_TIMEOUT_MS);
    }

    CelRuleCondition(ExecutorService evalPool, long evalTimeoutMs) {
        this.evalPool = evalPool;
        this.evalTimeoutMs = evalTimeoutMs;
    }

    /** 发布时编译；失败 → 400 + 行列定位。 */
    public CelRuntime.Program compileOrThrow(String ruleVersionId, String expression) {
        if (expression == null || expression.isBlank()) {
            return compileOrThrow(ruleVersionId, "true");
        }
        rejectBodyAccess(expression);
        CelValidationResult result = COMPILER.compile(expression);
        if (result.hasError()) {
            throw compileRejected(result);
        }
        try {
            CelAbstractSyntaxTree ast = result.getAst();
            CelRuntime.Program program = RUNTIME.createProgram(ast);
            if (ruleVersionId != null && !ruleVersionId.isBlank()) {
                cache.put(ruleVersionId, program);
            }
            return program;
        } catch (CelValidationException | CelEvaluationException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "CEL 编译失败: " + e.getMessage());
        }
    }

    public boolean evaluate(String ruleVersionId, String expression, Map<String, Object> event,
                            Map<String, String> attrs) {
        CelRuntime.Program cached = ruleVersionId == null ? null : cache.get(ruleVersionId);
        final CelRuntime.Program program = cached != null
                ? cached
                : compileOrThrow(ruleVersionId, expression);
        Map<String, Object> activation = Map.of(
                "event", event == null ? Map.of() : event,
                "attrs", attrs == null ? Map.of() : attrs);
        Future<Object> future = evalPool.submit(() -> {
            try {
                return program.eval(activation);
            } catch (CelEvaluationException e) {
                throw new RuntimeException(e);
            }
        });
        try {
            Object out = future.get(evalTimeoutMs, TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(out);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new CelEvalException("cel_timeout", "CEL 求值超时 " + evalTimeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re && re.getCause() instanceof CelEvaluationException ce) {
                throw new CelEvalException("cel_error", ce.getMessage());
            }
            if (cause instanceof CelEvaluationException ce) {
                throw new CelEvalException("cel_error", ce.getMessage());
            }
            throw new CelEvalException("cel_error", cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CelEvalException("cel_error", "CEL 求值中断");
        }
    }

    public void evict(String ruleVersionId) {
        if (ruleVersionId != null) {
            cache.remove(ruleVersionId);
        }
    }

    private static void rejectBodyAccess(String expression) {
        if (BODY_ACCESS.matcher(expression).find()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "CEL 不得访问 event.body（行列 1:1）");
        }
    }

    private static BizException compileRejected(CelValidationResult result) {
        CelIssue first = result.getErrors().isEmpty() ? null : result.getErrors().get(0);
        int line = 1;
        int col = 1;
        String msg = result.getErrorString();
        if (first != null) {
            CelSourceLocation loc = first.getSourceLocation();
            if (loc != null) {
                line = Math.max(1, loc.getLine());
                col = Math.max(1, loc.getColumn());
            }
            msg = first.getMessage();
        }
        log.debug("CEL 编译失败 line={} col={} msg={}", line, col, msg);
        return new BizException(ErrorCode.BAD_REQUEST,
                "CEL 编译失败（行列 " + line + ":" + col + "）: " + msg);
    }

    /** 求值失败（超时/异常），由调用方决定回落或 UNAVAILABLE。 */
    public static final class CelEvalException extends RuntimeException {
        private final String reason;

        public CelEvalException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
