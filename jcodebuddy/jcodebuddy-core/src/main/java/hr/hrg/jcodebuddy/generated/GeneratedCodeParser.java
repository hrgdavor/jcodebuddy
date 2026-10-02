package hr.hrg.jcodebuddy.generated;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A generic parser that finds the generated regions of a source file, using the file alone.
 *
 * <p>DEC-035 fixes a small marker vocabulary so that a tool which is not the generator can tell which
 * parts of a file the generator owns. This class is the recogniser that turns that vocabulary into line
 * spans. It knows <b>nothing</b> about any generator: no configuration, no type names, no list of
 * generators to ask. A file and the rules are the whole input, which is what makes it usable by a linter,
 * a migration tool, an IDE inspection or an agent.
 *
 * <p>What it does with the four scopes:
 *
 * <ul>
 *   <li>{@code file} — the block runs to the end of the file. Nothing is matched, so a file marker is
 *       always closed.</li>
 *   <li>{@code member} and {@code block} — the declaration or statement below the marker. Both are bounded
 *       by the construct's own braces: the next top-level <code>{</code> and its matching
 *       <code>}</code>. This is the rule that keeps a generated {@code switch} to one marker instead of
 *       one per {@code case} arm.</li>
 *   <li>{@code region} — a pair, {@code region begin <id>} through {@code region end <id>}.</li>
 * </ul>
 *
 * <h3>Why the brace matching is a lexer and not a brace counter</h3>
 *
 * <p>A plain count of {@code {} characters is wrong on real Java, and wrong quietly — it reports a span
 * that is too long or too short with no way for the caller to notice. The cases that break it are all
 * common: {@code "}"} inside a string literal, a {@code '{'} inside a text block, a brace inside a
 * comment, an escaped quote at the end of a string ({@code "\\"}). So this class tracks string literals,
 * text blocks, character literals, line comments and block comments while it counts, and skips their
 * contents.
 *
 * <p>It is deliberately not a full Java parser. It resolves braces, which is all a boundary needs, and it
 * treats anything it does not understand as ordinary text — the safe direction, because the alternative is
 * guessing a boundary.
 *
 * <h3>The one behaviour a caller must not ignore</h3>
 *
 * <p>{@link Result#issues()} lists markers this parser could not apply: a scope it does not implement, a
 * region whose {@code end} is missing, a {@code block} or {@code member} whose construct has no braces.
 * These are reported, not dropped and not guessed at, because DEC-035's rule is that generated code must
 * never be mistaken for hand-written. A caller that is about to <em>edit</em> a file should refuse when
 * {@link Result#hasIssues()} is true; a caller that is only reporting can list them and continue.
 */
public final class GeneratedCodeParser {

    /** The line terminator this parser splits on: any of LF, CRLF or CR, as Java's own reader accepts. */
    private static final java.util.regex.Pattern LINE_BREAK = java.util.regex.Pattern.compile("\\r\\n|\\r|\\n");

    private GeneratedCodeParser() {
    }

    /**
     * What a parse found.
     *
     * @param blocks the generated regions, in the order they appear, never overlapping
     * @param issues markers that could not be applied, in the order they appear
     * @param lines  how many lines the source has, so a caller can sanity-check a span
     */
    public record Result(List<GeneratedBlock> blocks, List<MarkerIssue> issues, int lines) {

        /** Whether any marker could not be applied, so a caller about to edit should refuse. */
        public boolean hasIssues() {
            return !issues.isEmpty();
        }

        /** Whether the file has any generated code at all. */
        public boolean hasGeneratedCode() {
            return !blocks.isEmpty();
        }

        /**
         * Whether any marker in the file claims more than this parser can prove.
         *
         * <p>True when an unknown marker exists. This is the check DEC-035 requires of an editor: code
         * under a marker it cannot read must not be treated as the developer's, so it must not be edited.
         */
        public boolean hasUnknownMarkers() {
            return issues.stream().anyMatch(issue -> issue.kind() == MarkerIssue.Kind.UNKNOWN_MARKER);
        }

        /** Whether the whole file is generated, judged by a file marker covering it. */
        public boolean isWhollyGenerated() {
            return blocks.stream().anyMatch(block -> block.kind() == GeneratedBlock.Kind.FILE);
        }

        /** The block containing a 1-based line, if any. */
        public Optional<GeneratedBlock> blockAt(int line) {
            for (GeneratedBlock block : blocks) {
                if (block.containsLine(line)) {
                    return Optional.of(block);
                }
            }
            return Optional.empty();
        }

        /**
         * The innermost block containing a line, if any.
         *
         * <p>"Innermost" matters because a span can contain another: a file marker covers a member marker
         * inside it. The most specific answer is the useful one — it tells a caller the smallest region it
         * must not hand-edit.
         */
        public Optional<GeneratedBlock> innermostBlockAt(int line) {
            GeneratedBlock best = null;
            for (GeneratedBlock block : blocks) {
                if (block.containsLine(line) && (best == null || block.lineCount() < best.lineCount())) {
                    best = block;
                }
            }
            return Optional.ofNullable(best);
        }

        /** Whether a 1-based line is inside any generated block. */
        public boolean isGenerated(int line) {
            return blockAt(line).isPresent();
        }
    }

    /**
     * One marker this parser could not apply, in DEC-022's diagnostic shape.
     *
     * @param kind   why it could not be applied
     * @param line   1-based line of the marker
     * @param detail what was found and what was expected, for a report
     */
    public record MarkerIssue(Kind kind, int line, String detail) {

        /** Why a marker could not be applied. */
        public enum Kind {

            /**
             * A marker whose scope this parser does not implement.
             *
             * <p>DEC-035's rule: recognisable, and refused. The code below it is generated by a rule this
             * parser cannot apply, and it must not be treated as hand-written.
             */
            UNKNOWN_MARKER("unknown_marker"),

            /** A {@code region begin} with no matching {@code region end}. */
            UNCLOSED_REGION("unclosed_region"),

            /** A {@code region end} with no matching {@code region begin}. */
            UNMATCHED_REGION_END("unmatched_region_end"),

            /**
             * A {@code block} or {@code member} marker whose construct has no braces to bound it.
             *
             * <p>DEC-035 requires the annotated construct to have its own braces. Given
             * {@code int value = 1;} there is nothing to bound the span with, and guessing — to the end of
             * the file, or to the next blank line — would invent a boundary.
             */
            MARKER_WITHOUT_BLOCK("marker_without_block"),

            /** A {@code block}/{@code member} whose opening brace is never closed. */
            UNCLOSED_BLOCK("unclosed_block");

            private final String id;

            Kind(String id) {
                this.id = id;
            }

            /** The stable id used in reports. */
            public String id() {
                return id;
            }
        }

        /** A report line, in the same shape the generator's own divergences use. */
        public String describe(String file) {
            return "kind=" + kind.id() + ", location=" + (file == null ? "<source>" : file)
                    + ":" + line + ", " + detail;
        }
    }

    /**
     * Parse a source file.
     *
     * @param source the whole file text
     */
    public static Result parse(String source) {
        return parse(source, null);
    }

    /**
     * Parse a source file, naming it in issue reports.
     *
     * @param source the whole file text
     * @param file   the name to use in a report, or {@code null}
     */
    public static Result parse(String source, String file) {
        if (source == null || source.isEmpty()) {
            return new Result(List.of(), List.of(), 0);
        }
        List<String> lines = List.of(LINE_BREAK.split(source, -1));
        List<GeneratedBlock> blocks = new ArrayList<>();
        List<MarkerIssue> issues = new ArrayList<>();

        // The char offset of each line's start, so a marker's line can be turned into an offset to search
        // from. Built once: finding a block's end is a scan over the whole text, and re-finding line starts
        // per marker would make the whole parse quadratic for no reason.
        int[] lineStart = lineStarts(source, lines.size());

        boolean fileClaimed = false;
        // Region pairs whose `begin` has been seen, by id, in the order they were opened.
        java.util.LinkedHashMap<String, GeneratedCodeMarkers.Found> openRegions = new java.util.LinkedHashMap<>();

        for (GeneratedCodeMarkers.Found marker : GeneratedCodeMarkers.scan(lines)) {
            if (marker.isFile()) {
                // A file marker claims everything below it. Recording it once is right: a second one adds
                // no information, and reporting it as an issue would be noise about a legal file.
                if (!fileClaimed) {
                    blocks.add(new GeneratedBlock(GeneratedBlock.Kind.FILE, marker, marker.line(),
                            lines.size(), null, true, marker.generator().orElse(null)));
                    fileClaimed = true;
                }
                continue;
            }

            Optional<GeneratedBlock.Kind> kind = GeneratedBlock.Kind.of(marker.scope());
            if (kind.isEmpty()) {
                issues.add(new MarkerIssue(MarkerIssue.Kind.UNKNOWN_MARKER, marker.line(),
                        "cause=the marker's scope is not one this parser implements"
                                + ", current=" + marker.label()
                                + ", canonical=" + String.join("/", GeneratedCodeMarkers.KNOWN_SCOPES)
                                + ", action=do not treat the code below this marker as hand-written: "
                                + "either support this marker or refuse to edit the file"));
                continue;
            }

            switch (kind.get()) {
                case FILE -> throw new IllegalStateException("handled above");
                case REGION -> parseRegion(marker, lines, openRegions, blocks, issues);
                case MEMBER, BLOCK -> parseBraceBounded(marker, kind.get(), source, lineStart, lines.size(),
                        blocks, issues);
            }
        }

        // A region left open at end of file. Reported rather than extended, because a missing `end` is a
        // broken marker and extending it would silently claim the rest of the file.
        for (GeneratedCodeMarkers.Found open : openRegions.values()) {
            issues.add(new MarkerIssue(MarkerIssue.Kind.UNCLOSED_REGION, open.line(),
                    "cause=a region begin has no matching region end by end of file"
                            + ", current=" + open.label() + " id=" + open.regionId().orElse("?")
                            + ", canonical=a region end with the same id"
                            + ", action=add the missing end marker; the region is reported unclosed rather "
                            + "than extended to the end of the file"));
        }

        return new Result(List.copyOf(blocks), List.copyOf(issues), lines.size());
    }

    /** A region pair: `begin <id>` through the matching `end <id>`. */
    private static void parseRegion(GeneratedCodeMarkers.Found marker, List<String> lines,
                                    java.util.LinkedHashMap<String, GeneratedCodeMarkers.Found> openRegions,
                                    List<GeneratedBlock> blocks, List<MarkerIssue> issues) {
        Optional<String> id = marker.regionId();
        if (id.isEmpty()) {
            // `// @generated region` with no direction or id: a marker this parser cannot pair, and it
            // cannot be treated as unscoped either — it claims a region with no way to find its end.
            issues.add(new MarkerIssue(MarkerIssue.Kind.UNKNOWN_MARKER, marker.line(),
                    "cause=a region marker with no direction word and id"
                            + ", current=" + marker.text()
                            + ", canonical=" + GeneratedCodeMarkers.regionBegin("<id>", "<generator>")
                            + " or " + GeneratedCodeMarkers.regionEnd("<id>")
                            + ", action=do not treat the code below this marker as hand-written"));
            return;
        }

        if (!marker.isRegionEnd()) {
            openRegions.put(id.get(), marker);
            return;
        }

        GeneratedCodeMarkers.Found begin = openRegions.remove(id.get());
        if (begin == null) {
            issues.add(new MarkerIssue(MarkerIssue.Kind.UNMATCHED_REGION_END, marker.line(),
                    "cause=a region end has no matching region begin"
                            + ", current=" + marker.label() + " id=" + id.get()
                            + ", canonical=a region begin with the same id"
                            + ", action=add the opening marker or remove this one"));
            return;
        }
        blocks.add(new GeneratedBlock(GeneratedBlock.Kind.REGION, begin, begin.line(), marker.line(),
                id.get(), true, begin.generator().orElse(null)));
    }

    /**
     * A member or a block: the next top-level <code>{</code> below the marker, and its matching
     * <code>}</code>.
     *
     * <p>Both scopes are found the same way, and that is not an accident: DEC-035 defines {@code block} as
     * "the statement below, bounded by the language's braces" and {@code member} as "the declaration below
     * and its body". For a method those are the same span; the difference is what the caller is told, which
     * is why the kind is recorded rather than the two being folded together.
     */
    private static void parseBraceBounded(GeneratedCodeMarkers.Found marker, GeneratedBlock.Kind kind,
                                          String source, int[] lineStart, int lineCount,
                                          List<GeneratedBlock> blocks, List<MarkerIssue> issues) {
        int from = lineStart[marker.line() - 1];
        // Start after the marker's own line: the marker is a line comment, so its own text cannot contain
        // the brace we are looking for, but searching from the next line keeps that a property rather than
        // a coincidence.
        int searchFrom = marker.line() < lineCount
                ? lineStart[marker.line()]
                : source.length();

        int open = openBraceOfConstruct(source, searchFrom);
        if (open < 0) {
            issues.add(new MarkerIssue(MarkerIssue.Kind.MARKER_WITHOUT_BLOCK, marker.line(),
                    "cause=the marker is not followed by a construct with braces"
                            + ", current=" + marker.label()
                            + ", canonical=" + marker.label() + " immediately above a declaration or "
                            + "statement whose body has its own braces"
                            + ", action=DEC-035 requires the annotated construct to have braces, because "
                            + "otherwise there is no boundary to find and guessing one would invent it"));
            return;
        }

        int close = matchingBrace(source, open);
        if (close < 0) {
            issues.add(new MarkerIssue(MarkerIssue.Kind.UNCLOSED_BLOCK, marker.line(),
                    "cause=the opening brace below the marker is never closed"
                            + ", current=" + marker.label()
                            + ", canonical=a matching closing brace"
                            + ", action=the file does not parse; the block is reported unclosed rather "
                            + "than extended to the end of the file"));
            return;
        }

        int toLine = lineOfOffset(lineStart, close);
        blocks.add(new GeneratedBlock(kind, marker, marker.line(), toLine, null, true,
                marker.generator().orElse(null)));
    }

    private static int[] lineStarts(String source, int lineCount) {
        int[] starts = new int[lineCount];
        starts[0] = 0;
        int line = 1;
        for (int i = 0; i < source.length() && line < lineCount; i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                starts[line++] = i + 1;
            } else if (c == '\r') {
                if (i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
                starts[line++] = i + 1;
            }
        }
        return starts;
    }

    /** The 1-based line containing a char offset. */
    private static int lineOfOffset(int[] lineStart, int offset) {
        int low = 0;
        int high = lineStart.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (lineStart[mid] <= offset) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low + 1;
    }

    /**
     * The opening brace of the construct that starts at or after {@code from}.
     *
     * <p>Returns {@code -1} when the construct has no braces, which is the case DEC-035 forbids marking.
     * The scan stops at a top-level <code>;</code> for that reason: a field declaration or an abstract
     * method ends there, and continuing past it would find the brace of an unrelated construct further
     * down and report a span that includes code the generator never wrote.
     */
    private static int openBraceOfConstruct(String source, int from) {
        Lexer lexer = new Lexer(source, from);
        while (lexer.hasNext()) {
            char c = lexer.next();
            if (c == '{') {
                return lexer.offset() - 1;
            }
            if (c == ';') {
                return -1;
            }
            // A closing brace before an opening one means the marker sat at the end of an enclosing body.
            // There is no construct to bound, and the next `{` would belong to code outside it.
            if (c == '}') {
                return -1;
            }
        }
        return -1;
    }

    /**
     * The offset of the brace that closes the one at {@code open}, or {@code -1} if it never closes.
     *
     * <p>Nesting is counted over lexed characters only, so a brace inside a string, a text block or a
     * comment cannot end a block early. That is the whole reason this is a lexer.
     */
    private static int matchingBrace(String source, int open) {
        int depth = 0;
        Lexer lexer = new Lexer(source, open);
        while (lexer.hasNext()) {
            char c = lexer.next();
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return lexer.offset() - 1;
                }
            }
        }
        return -1;
    }

    /**
     * A character reader that skips over the places a brace character is not a brace.
     *
     * <p>Handles the five that matter in Java: a string literal, a text block, a character literal, a line
     * comment and a block comment. Everything else is returned as-is; anything the lexer does not
     * understand becomes ordinary text, which is the safe direction because the alternative is inventing a
     * boundary.
     */
    private static final class Lexer {

        private final String source;
        private int offset;

        Lexer(String source, int from) {
            this.source = source;
            this.offset = Math.max(0, Math.min(from, source.length()));
        }

        boolean hasNext() {
            return offset < source.length();
        }

        /** The offset just past the character last returned. */
        int offset() {
            return offset;
        }

        char next() {
            char c = source.charAt(offset++);
            if (c == '"') {
                skipString();
            } else if (c == '\'') {
                skipCharLiteral();
            } else if (c == '/' && offset < source.length()) {
                char d = source.charAt(offset);
                if (d == '/') {
                    skipLineComment();
                } else if (d == '*') {
                    skipBlockComment();
                }
            }
            return c;
        }

        /** After a {@code "}: a text block if two more quotes follow, otherwise a string. */
        private void skipString() {
            if (offset + 1 < source.length()
                    && source.charAt(offset) == '"' && source.charAt(offset + 1) == '"') {
                offset += 2;
                while (offset < source.length()) {
                    char c = source.charAt(offset++);
                    if (c == '\\') {
                        offset++;
                    } else if (c == '"' && offset + 1 < source.length()
                            && source.charAt(offset) == '"' && source.charAt(offset + 1) == '"') {
                        // A text block ends at three quotes. Escapes are handled above, so this is the
                        // terminator and not an escaped quote.
                        offset += 2;
                        return;
                    }
                }
                return;
            }
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '\\') {
                    offset++;
                } else if (c == '"') {
                    return;
                }
            }
        }

        private void skipCharLiteral() {
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '\\') {
                    offset++;
                } else if (c == '\'') {
                    return;
                }
            }
        }

        private void skipLineComment() {
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '\n' || c == '\r') {
                    return;
                }
            }
        }

        private void skipBlockComment() {
            while (offset < source.length()) {
                char c = source.charAt(offset++);
                if (c == '*' && offset < source.length() && source.charAt(offset) == '/') {
                    offset++;
                    return;
                }
            }
        }
    }
}
