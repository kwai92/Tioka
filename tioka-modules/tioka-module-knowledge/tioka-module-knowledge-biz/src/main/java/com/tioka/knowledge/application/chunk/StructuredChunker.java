package com.tioka.knowledge.application.chunk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import com.tioka.ontology.api.docparse.ParsedUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KNW-03：结构感知切块（M3-06 §3.2 / ADR-055 §2）。纯函数：输入 ParseResult（KNW-11 结构化
 * 解析产物）+ 切块策略，输出父子块序列（先父后子；父块无向量仅供上下文）。
 *
 * <ul>
 *   <li>只消费 role=BODY 的块（FURNITURE/ANNOTATION 不进切块）；图块（IMAGE）丢弃，只保留 CAPTION。</li>
 *   <li>按标题切父块（一个小节，上限默认 2000 字，超限按段落边界拆为多个父块、标题路径相同）；
 *       全文不超过父块上限 → 整篇一个父块（小文档）。</li>
 *   <li>父块内切子块：默认约 400 字、重叠 50 字，按句子边界（。！？!?/换行）不切断句子；
 *       单句超上限才硬切（上限不超过嵌入 binding 单条输入上限，经策略 maxChildChars 配置）。</li>
 *   <li>标题路径前缀（文档标题 &gt; 各级标题）：子块嵌入与全文索引时正文前加（contextEnhancement 开关），
 *       展示与送模型不加——存 {@code context_prefix} 列，检索/展示侧自行决定是否拼接。</li>
 *   <li>表格：整表 ≤1500 字一个子块（Markdown 形式）；更大按行组切块、每块重复表头行；
 *       表格前后最近一段说明文字作上下文前缀。DOC 域 CELL 单元按顺序聚合（table_ref 列信息
 *       未暴露，登记偏差）；Markdown 管道表/带 tableRef 的样本走完整行列聚合。</li>
 *   <li>FAQ 型：段落/标题以 ？/? 结尾且后接回答，自动模式检测 ≥2 组问答即按 FAQ 切
 *       （每组问答一个父块）；策略 mode=faq 强制、heading 强制标题模式。</li>
 *   <li>页码与位置：每块记起止页与来源 blockId 列表（坐标经 blockId 从解析结果取，本批不落坐标）。</li>
 * </ul>
 */
public final class StructuredChunker {

    public static final String TYPE_PARENT = "PARENT";
    public static final String TYPE_CHILD = "CHILD";

    public static final int DEFAULT_PARENT_MAX_CHARS = 2000;
    public static final int DEFAULT_CHILD_TARGET_CHARS = 400;
    public static final int DEFAULT_CHILD_OVERLAP_CHARS = 50;
    public static final int TABLE_SINGLE_BLOCK_MAX_CHARS = 1500;

    /** 切块策略（kb_base.chunk_policy_json；NULL/空 = 自动缺省）。 */
    public record ChunkPolicy(String mode, Integer parentMaxChars, Integer childTargetChars,
                              Integer childOverlapChars, Integer maxChildChars) {
        public static ChunkPolicy auto() {
            return new ChunkPolicy("auto", null, null, null, null);
        }

        /** ENG-05：ObjectMapper 由调用方注入（统一 Spring Bean），本类不直接构造。 */
        public static ChunkPolicy fromJson(String json, ObjectMapper mapper) {
            if (json == null || json.isBlank()) {
                return auto();
            }
            try {
                ChunkPolicy p = mapper.readValue(json, ChunkPolicy.class);
                return new ChunkPolicy(
                        p.mode() == null ? "auto" : p.mode(),
                        p.parentMaxChars(), p.childTargetChars(),
                        p.childOverlapChars(), p.maxChildChars());
            } catch (Exception e) {
                return auto();
            }
        }

        public int parentMax() {
            return parentMaxChars == null || parentMaxChars < 1 ? DEFAULT_PARENT_MAX_CHARS : parentMaxChars;
        }

        public int childTarget() {
            return childTargetChars == null || childTargetChars < 1
                    ? DEFAULT_CHILD_TARGET_CHARS : childTargetChars;
        }

        public int childOverlap() {
            return childOverlapChars == null || childOverlapChars < 0
                    ? DEFAULT_CHILD_OVERLAP_CHARS : childOverlapChars;
        }

        /** 子块硬上限：缺省 = 目标 + 80（嵌入输入上限由策略显式收紧，不越 target）。 */
        public int childMax() {
            if (maxChildChars == null || maxChildChars < 1) {
                return childTarget() + 80;
            }
            return Math.min(maxChildChars, Math.max(maxChildChars, 1));
        }
    }

    /** 输出块：PARENT 行（无向量）或 CHILD 行；parentOrdinal 为所属父块在输出中的序号（父块为 -1）。 */
    public record ChunkPiece(String chunkType, int parentOrdinal, String text, String contextPrefix,
                             String headingPath, int pageStart, int pageEnd, List<String> blockIds) {
    }

    // ------------------------------------------------------------------

    /** 内部块：表格已聚合（type=TABLE，text 为 Markdown 行；DOC 域 CELL 单元顺序聚合）。 */
    private record Block(String type, int level, String text, String blockId, int page, int pageEnd) {
        Block(String type, int level, String text, String blockId, int page) {
            this(type, level, text, blockId, page, page);
        }
    }

    /** 内部小节：一个父块候选。 */
    private record Section(String headingPath, List<Block> blocks, boolean faqQuestion) {
    }

    private final ChunkPolicy policy;
    private final boolean contextEnhancement;

    public StructuredChunker(ChunkPolicy policy, boolean contextEnhancement) {
        this.policy = policy == null ? ChunkPolicy.auto() : policy;
        this.contextEnhancement = contextEnhancement;
    }

    public List<ChunkPiece> chunk(ParseResult result, String docTitle) {
        List<Block> blocks = normalize(result == null ? List.of() : result.units());
        int totalChars = blocks.stream().mapToInt(b -> b.text().length()).sum();
        boolean hasHeadings = blocks.stream()
                .anyMatch(b -> ParseContract.T_HEADING.equals(b.type()) && b.level() > 0);

        List<Section> sections;
        if (isFaq(blocks)) {
            // FAQ 型优先：每组问答一个父块（小 FAQ 文档也按问答切）
            sections = faqSections(blocks);
        } else if (totalChars <= policy.parentMax() && !hasHeadings) {
            // 小文档且无标题结构：整篇一个父块
            sections = List.of(new Section("", blocks, false));
        } else {
            sections = headingSections(blocks);
        }

        List<ChunkPiece> out = new ArrayList<>();
        String title = docTitle == null ? "" : docTitle.strip();
        for (Section section : sections) {
            String prefix = contextEnhancement
                    ? joinPath(title, section.headingPath()) : "";
            for (List<Block> part : splitByLimit(section.blocks(), policy.parentMax())) {
                int parentOrdinal = out.size();
                Integer[] pages = pageRange(part.stream().mapToInt(Block::page).toArray());
                List<String> parentIds = part.stream().map(Block::blockId).distinct().toList();
                String parentText = joinText(part);
                out.add(new ChunkPiece(TYPE_PARENT, -1, parentText, prefix, section.headingPath(),
                        pages[0], pages[1], parentIds));

                for (ChunkPiece child : children(part, prefix)) {
                    out.add(new ChunkPiece(TYPE_CHILD, parentOrdinal, child.text(),
                            child.contextPrefix(), child.headingPath(),
                            child.pageStart(), child.pageEnd(), child.blockIds()));
                }
            }
        }
        if (out.isEmpty()) {
            out.add(new ChunkPiece(TYPE_PARENT, -1, "", "", "", 1, 1, List.of()));
            out.add(new ChunkPiece(TYPE_CHILD, 0, "", "", "", 1, 1, List.of()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 预处理：BODY 过滤 + 表格聚合
    // ------------------------------------------------------------------

    private List<Block> normalize(List<ParsedUnit> units) {
        // 带 tableRef 的单元按 tableId 聚合、行列排序重建 Markdown 表（快照样本路径）
        Map<Integer, List<ParsedUnit>> refTables = new LinkedHashMap<>();
        for (ParsedUnit u : units) {
            if (u.tableRef() != null) {
                refTables.computeIfAbsent(u.tableRef().tableId(), k -> new ArrayList<>()).add(u);
            }
        }
        List<Block> out = new ArrayList<>();
        // DOC 域 CELL/BLANK_FIELD 连续单元聚合为表（table_ref 列信息未暴露，顺序即行序）
        List<ParsedUnit> cellRun = new ArrayList<>();
        for (ParsedUnit u : units) {
            if (u.tableRef() != null) {
                flushCells(out, cellRun);
                if (!emittedTable(out, u.tableRef().tableId(), refTables)) {
                    continue;
                }
                continue;
            }
            String role = u.role() == null ? ParseContract.R_BODY : u.role();
            if (!ParseContract.R_BODY.equals(role)) {
                continue; // FURNITURE / ANNOTATION 不进切块
            }
            String type = u.type() == null ? ParseContract.T_PARAGRAPH : u.type();
            if ("IMAGE".equals(type)) {
                continue; // 图块丢弃，只保留 CAPTION（CAPTION 为契约 v1 允许的扩展取值）
            }
            String text = u.text() == null ? "" : u.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            boolean cell = "CELL".equals(type) || ParseContract.T_TABLE_CELL.equals(type);
            if (cell || (!cellRun.isEmpty() && ParseContract.T_BLANK_FIELD.equals(type))) {
                cellRun.add(u);
                continue;
            }
            flushCells(out, cellRun);
            if ("CAPTION".equals(type)) {
                out.add(new Block(ParseContract.T_PARAGRAPH, 0, text, u.blockId(), pageOf(u)));
            } else if (ParseContract.T_TABLE.equals(type)) {
                out.add(new Block(ParseContract.T_TABLE, 0, text, u.blockId(), pageOf(u)));
            } else {
                out.add(new Block(type, u.level(), text, u.blockId(), pageOf(u)));
            }
        }
        flushCells(out, cellRun);
        return out;
    }

    /** tableRef 聚合表落位（首个单元格位置插入整表，后续单元格跳过）；返回是否本次落位。 */
    private boolean emittedTable(List<Block> out, int tableId, Map<Integer, List<ParsedUnit>> refTables) {
        for (Block b : out) {
            if (ParseContract.T_TABLE.equals(b.type()) && (b.blockId().startsWith("tbl:" + tableId + ":"))) {
                return false; // 已落位
            }
        }
        List<ParsedUnit> cells = refTables.get(tableId);
        if (cells == null || cells.isEmpty()) {
            return false;
        }
        Map<Integer, Map<Integer, String>> rows = new LinkedHashMap<>();
        for (ParsedUnit c : cells) {
            rows.computeIfAbsent(c.tableRef().row(), k -> new LinkedHashMap<>())
                    .put(c.tableRef().col(), c.text() == null ? "" : c.text().strip());
        }
        StringBuilder sb = new StringBuilder();
        for (Map<Integer, String> row : rows.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(String.join(" | ", row.values()));
        }
        int pageMin = Integer.MAX_VALUE;
        int pageMax = 0;
        for (ParsedUnit c : cells) {
            pageMin = Math.min(pageMin, pageOf(c));
            pageMax = Math.max(pageMax, pageOf(c));
        }
        out.add(new Block(ParseContract.T_TABLE, 0, sb.toString(),
                "tbl:" + tableId + ":" + cells.get(0).blockId(), Math.max(pageMin, 1), Math.max(pageMax, 1)));
        return true;
    }

    private static void flushCells(List<Block> out, List<ParsedUnit> run) {
        if (run.isEmpty()) {
            return;
        }
        boolean hasCell = run.stream().anyMatch(u -> "CELL".equals(u.type())
                || ParseContract.T_TABLE_CELL.equals(u.type()));
        if (!hasCell) {
            for (ParsedUnit u : run) {
                out.add(new Block(ParseContract.T_BLANK_FIELD, 0, u.text().strip(),
                        u.blockId(), pageOf(u)));
            }
        } else {
            // 每个单元一行（无列信息，登记偏差）；跨页取首块页码
            StringBuilder sb = new StringBuilder();
            for (ParsedUnit u : run) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(u.text().strip());
            }
            out.add(new Block(ParseContract.T_TABLE, 0, sb.toString(),
                    run.get(0).blockId() + ".." + run.get(run.size() - 1).blockId(),
                    pageOf(run.get(0))));
        }
        run.clear();
    }

    private static int pageOf(ParsedUnit u) {
        if (u.regions() == null || u.regions().isEmpty()) {
            return 1;
        }
        return Math.max(1, u.regions().get(0).pageNo());
    }

    // ------------------------------------------------------------------
    // 小节切分
    // ------------------------------------------------------------------

    private boolean isFaq(List<Block> blocks) {
        if ("heading".equals(policy.mode())) {
            return false;
        }
        int pairs = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (isQuestion(b)) {
                boolean hasAnswer = false;
                for (int j = i + 1; j < blocks.size() && !isQuestion(blocks.get(j)); j++) {
                    hasAnswer = true;
                }
                if (hasAnswer) {
                    pairs++;
                }
            }
        }
        if ("faq".equals(policy.mode())) {
            return pairs >= 1;
        }
        return pairs >= 2;
    }

    private static boolean isQuestion(Block b) {
        String t = b.text();
        return (ParseContract.T_PARAGRAPH.equals(b.type()) || ParseContract.T_HEADING.equals(b.type()))
                && (t.endsWith("？") || t.endsWith("?"));
    }

    private List<Section> faqSections(List<Block> blocks) {
        List<Section> out = new ArrayList<>();
        List<Block> pending = new ArrayList<>();
        for (Block b : blocks) {
            if (isQuestion(b)) {
                if (!pending.isEmpty()) {
                    out.add(new Section("", List.copyOf(pending), false));
                    pending = new ArrayList<>();
                }
                pending.add(b);
            } else {
                pending.add(b);
            }
        }
        if (!pending.isEmpty()) {
            out.add(new Section("", List.copyOf(pending), false));
        }
        return out;
    }

    private List<Section> headingSections(List<Block> blocks) {
        List<Section> out = new ArrayList<>();
        List<String> stack = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        String currentPath = "";
        for (Block b : blocks) {
            if (ParseContract.T_HEADING.equals(b.type()) && b.level() > 0) {
                if (!current.isEmpty()) {
                    out.add(new Section(currentPath, List.copyOf(current), false));
                    current = new ArrayList<>();
                }
                while (stack.size() >= b.level()) {
                    stack.remove(stack.size() - 1);
                }
                stack.add(b.text());
                currentPath = String.join(" > ", stack);
            } else {
                current.add(b);
            }
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(new Section(currentPath, List.copyOf(current), false));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 父块超限拆分（段落边界；单段超限才硬切）
    // ------------------------------------------------------------------

    private List<List<Block>> splitByLimit(List<Block> blocks, int limit) {
        List<List<Block>> out = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        int size = 0;
        for (Block b : blocks) {
            // 表格原子性：整块加入当前父块（不参与拆分；行组切分在子块层完成）。
            // 表格可能使父块超上限——原子性优先，登记口径。
            if (ParseContract.T_TABLE.equals(b.type())) {
                current.add(b);
                size += b.text().length();
                continue;
            }
            if (b.text().length() > limit) {
                if (!current.isEmpty()) {
                    out.add(List.copyOf(current));
                    current = new ArrayList<>();
                    size = 0;
                }
                for (String piece : hardSplit(b.text(), limit)) {
                    out.add(List.of(new Block(b.type(), b.level(), piece, b.blockId(), b.page())));
                }
                continue;
            }
            if (size + b.text().length() > limit && !current.isEmpty()) {
                out.add(List.copyOf(current));
                current = new ArrayList<>();
                size = 0;
            }
            current.add(b);
            size += b.text().length();
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(List.copyOf(current));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 子块切分（句子边界 + 重叠；表格整块/行组）
    // ------------------------------------------------------------------

    private List<ChunkPiece> children(List<Block> blocks, String prefix) {
        List<ChunkPiece> out = new ArrayList<>();
        for (Block b : blocks) {
            if (ParseContract.T_TABLE.equals(b.type())) {
                out.addAll(tableChildren(b, blocks, prefix));
            } else {
                List<Sent> sents = Sent.of(b);
                out.addAll(groupChildren(sents, prefix, b.text()));
            }
        }
        return out;
    }

    /** 表格子块：≤1500 字整表一块；否则按行组切、每块重复表头行（首行）。 */
    private List<ChunkPiece> tableChildren(Block table, List<Block> sectionBlocks, String prefix) {
        String tableText = table.text();
        String context = tableContextPrefix(table, sectionBlocks, prefix);
        if (tableText.length() <= TABLE_SINGLE_BLOCK_MAX_CHARS) {
            return List.of(new ChunkPiece(TYPE_CHILD, -1, tableText, context, "",
                    table.page(), table.pageEnd(), List.of(table.blockId())));
        }
        String[] lines = tableText.split("\n", -1);
        String header = lines.length > 0 ? lines[0] : "";
        int headerLen = header.length() + 1;
        List<ChunkPiece> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        for (int i = 1; i < lines.length; i++) {
            if (current.length() + lines[i].length() + 1 + headerLen > TABLE_SINGLE_BLOCK_MAX_CHARS
                    && current.length() > 0) {
                out.add(tablePiece(header, current.toString(), table, startLine, i));
                current = new StringBuilder();
                startLine = i;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(lines[i]);
        }
        if (current.length() > 0 || out.isEmpty()) {
            out.add(tablePiece(header, current.toString(), table, startLine, lines.length));
        }
        for (int i = 0; i < out.size(); i++) {
            ChunkPiece p = out.get(i);
            out.set(i, new ChunkPiece(TYPE_CHILD, -1, p.text(), context, "",
                    p.pageStart(), p.pageEnd(), p.blockIds()));
        }
        return out;
    }

    private ChunkPiece tablePiece(String header, String rows, Block table, int fromLine, int toLine) {
        String text = header.isBlank() ? rows : header + "\n" + rows;
        return new ChunkPiece(TYPE_CHILD, -1, text, "", "",
                table.page(), table.pageEnd(), List.of(table.blockId()));
    }

    /** 表格前后最近一段说明文字（段落，非标题/表格）作上下文前缀。 */
    private String tableContextPrefix(Block table, List<Block> sectionBlocks, String prefix) {
        Block nearest = null;
        int idx = sectionBlocks.indexOf(table);
        for (int i = idx - 1; i >= 0 && nearest == null; i--) {
            Block b = sectionBlocks.get(i);
            if (ParseContract.T_PARAGRAPH.equals(b.type())) {
                nearest = b;
            }
        }
        for (int i = idx + 1; i < sectionBlocks.size() && nearest == null; i++) {
            Block b = sectionBlocks.get(i);
            if (ParseContract.T_PARAGRAPH.equals(b.type())) {
                nearest = b;
            }
        }
        if (nearest == null) {
            return prefix;
        }
        String note = nearest.text().length() > 100
                ? nearest.text().substring(0, 100) : nearest.text();
        return prefix.isBlank() ? "（表说明：" + note + "）"
                : prefix + "（表说明：" + note + "）";
    }

    private List<ChunkPiece> groupChildren(List<Sent> sents, String prefix, String fallbackText) {
        int target = policy.childTarget();
        int overlap = policy.childOverlap();
        int max = policy.childMax();
        List<ChunkPiece> out = new ArrayList<>();
        if (sents.isEmpty()) {
            out.add(new ChunkPiece(TYPE_CHILD, -1, "", prefix, "", 1, 1, List.of()));
            return out;
        }
        List<Sent> current = new ArrayList<>();
        int size = 0;
        for (int i = 0; i < sents.size(); i++) {
            Sent s = sents.get(i);
            if (s.text().length() > max) {
                if (!current.isEmpty()) {
                    out.add(pieceOf(current, prefix));
                    current = new ArrayList<>();
                    size = 0;
                }
                for (String piece : hardSplit(s.text(), max)) {
                    out.add(new ChunkPiece(TYPE_CHILD, -1, piece, prefix, "",
                            s.page(), s.page(), List.of(s.blockId())));
                }
                continue;
            }
            if (size + s.text().length() > target && !current.isEmpty()) {
                out.add(pieceOf(current, prefix));
                // 重叠：取上一块尾部句子（总长 ≤ overlap，且至少保留一块主体）
                List<Sent> tail = new ArrayList<>();
                int tailLen = 0;
                for (int j = current.size() - 1; j >= 0; j--) {
                    Sent t = current.get(j);
                    if (tailLen + t.text().length() > overlap || j == 0) {
                        break;
                    }
                    tail.add(0, t);
                    tailLen += t.text().length();
                }
                current = new ArrayList<>(tail);
                size = tailLen;
            }
            current.add(s);
            size += s.text().length();
        }
        if (!current.isEmpty() || out.isEmpty()) {
            out.add(pieceOf(current, prefix));
        }
        return out;
    }

    private ChunkPiece pieceOf(List<Sent> sents, String prefix) {
        StringBuilder sb = new StringBuilder();
        int pageStart = Integer.MAX_VALUE;
        int pageEnd = 0;
        List<String> ids = new ArrayList<>();
        for (Sent s : sents) {
            sb.append(s.text());
            pageStart = Math.min(pageStart, s.page());
            pageEnd = Math.max(pageEnd, s.page());
            if (!ids.contains(s.blockId())) {
                ids.add(s.blockId());
            }
        }
        if (pageStart == Integer.MAX_VALUE) {
            pageStart = 1;
            pageEnd = Math.max(1, pageEnd);
        }
        return new ChunkPiece(TYPE_CHILD, -1, sb.toString(), prefix, "", pageStart, pageEnd, ids);
    }

    // ------------------------------------------------------------------

    private static String joinPath(String title, String headingPath) {
        if (title.isBlank()) {
            return headingPath;
        }
        return headingPath.isBlank() ? title : title + " > " + headingPath;
    }

    private static String joinText(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(b.text());
        }
        return sb.toString();
    }

    private static Integer[] pageRange(int[] pages) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int p : pages) {
            min = Math.min(min, p);
            max = Math.max(max, p);
        }
        if (min == Integer.MAX_VALUE) {
            min = 1;
            max = Math.max(1, max);
        }
        return new Integer[]{min, max};
    }

    private static List<String> hardSplit(String text, int limit) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < text.length(); i += limit) {
            out.add(text.substring(i, Math.min(text.length(), i + limit)));
        }
        return out;
    }

    /** 句子（含来源块与页码）：按 。！？!? 与换行切，保留终止符。 */
    private record Sent(String text, String blockId, int page) {
        static List<Sent> of(Block b) {
            List<Sent> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < b.text().length(); i++) {
                char c = b.text().charAt(i);
                cur.append(c);
                if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n') {
                    out.add(new Sent(cur.toString(), b.blockId(), b.page()));
                    cur = new StringBuilder();
                }
            }
            if (cur.length() > 0) {
                out.add(new Sent(cur.toString(), b.blockId(), b.page()));
            }
            return out;
        }
    }
}
