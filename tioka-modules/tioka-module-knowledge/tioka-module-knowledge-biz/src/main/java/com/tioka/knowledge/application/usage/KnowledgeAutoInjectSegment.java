package com.tioka.knowledge.application.usage;

import com.tioka.framework.error.BizException;
import com.tioka.knowledge.application.KnowledgeService;
import com.tioka.runtime.api.context.ContextRenderContext;
import com.tioka.runtime.api.context.ContextSegmentProvider;
import com.tioka.runtime.api.context.ContextZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KNW-14 / CTX-01：{@code AUTO} / {@code BOTH} 模式的 D 区「知识自动注入」段提供者
 * （ADR-056 §3、ADR-048 第 1 节）。
 *
 * <p>每个 Turn 检索一次，结果带引用号放到本轮尾部；由 {@code ContextBuilder} 按
 * {@code turnId} 冻结，同一 Turn 多步不会重复检索、前缀哈希保持稳定。
 * 预算：最多 5 个父块 / 3000 token（粗估 9000 字符），超出按高分到低分裁剪。
 * 无答案时按 ADR-056 §2 写「知识库中没有找到与问题相关的内容」，让模型如实说明。
 *
 * <p>开关 {@code tioka.knowledge.auto-inject.enabled} 缺省关闭；关闭时不注册该段，
 * 也不改变任何既有 Turn 的上下文。
 */
@Component
@ConditionalOnProperty(prefix = "tioka.knowledge.auto-inject", name = "enabled", havingValue = "true")
public class KnowledgeAutoInjectSegment implements ContextSegmentProvider {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeAutoInjectSegment.class);
    /** ADR-056 §3：缺省 3000 token、最多 5 个父块。 */
    private static final int MAX_BLOCKS = 5;
    private static final int MAX_CHARS = 9000;

    private final KnowledgeService knowledge;
    /** 只读探针：闸门用它观测「同一 Turn 多步没有重新检索」，比读缓存 Map 更可证伪。 */
    private final AtomicLong renderCalls = new AtomicLong();

    public KnowledgeAutoInjectSegment(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    public long renderCalls() { return renderCalls.get(); }

    @Override
    public String segmentName() { return "knowledge.auto_inject"; }

    @Override
    public ContextZone zone() { return ContextZone.D_TURN_TAIL; }

    @Override
    public int order() { return 90; }

    @Override
    public double budgetShare() { return 0.25d; }

    @Override
    public String render(ContextRenderContext context) {
        renderCalls.incrementAndGet();
        if (context == null || context.query() == null || context.query().isBlank()) {
            return "";
        }
        try {
            KnowledgeService.RetrievalOutcome outcome = knowledge.retrieveDetailed(
                    null, context.query(), MAX_BLOCKS, null);
            if (outcome.hits().isEmpty()) {
                return "知识库中没有找到与问题相关的内容（原因：" + outcome.reason()
                        + "），请如实说明未找到，不要凭空作答。";
            }
            StringBuilder sb = new StringBuilder("以下知识片段可用于回答，采用时请标注引用号：\n");
            for (KnowledgeService.RetrievalHit hit : outcome.hits()) {
                String body = hit.parentText() == null || hit.parentText().isBlank()
                        ? hit.text() : hit.parentText();
                sb.append('[').append(hit.citationId()).append("] ")
                        .append(hit.title()).append('\n')
                        .append(body == null ? "" : body).append('\n');
                if (sb.length() >= MAX_CHARS) {
                    break;
                }
            }
            return sb.length() > MAX_CHARS ? sb.substring(0, MAX_CHARS) : sb.toString();
        } catch (BizException e) {
            if (e.getErrorCode().mustNotDegrade()) {
                // ADR-017：治理拒绝与 DEPENDENCY 兜底包装都不得降级成「知识库里没有相关内容」。
                throw e;
            }
            log.warn("知识自动注入失败 turnId={}: {}", context.turnId(), e.toString());
            return "";
        } catch (RuntimeException e) {
            // 段渲染失败不得阻断 Turn：退化为空注入并留日志（ContextBuilder 也会兜底）。
            log.warn("知识自动注入失败 turnId={}: {}", context.turnId(), e.toString());
            return "";
        }
    }

    /** 供 KnowledgeModeIT 断言预算上限，避免魔法数字散落。 */
    public static List<String> budgetConstants() {
        return List.of("maxBlocks=" + MAX_BLOCKS, "maxChars=" + MAX_CHARS);
    }
}
