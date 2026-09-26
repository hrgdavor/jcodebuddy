package hr.hrg.jcodebuddy.generated;

import java.util.Optional;

/**
 * A generated region found in a source file: which lines a generator owns, and why.
 *
 * <p>A block is a <b>line span</b>, inclusive at both ends, and the marker line is part of its own span.
 * That is the choice that makes the type usable for replacement: a tool that rewrites the generator's
 * output replaces exactly {@link #fromLine()}..{@link #toLine()} and the marker is replaced with it,
 * rather than being left behind as an orphan or duplicated.
 *
 * <p>{@link #kind()} says how the end was decided, which is the thing a consumer has to reason about:
 *
 * <ul>
 *   <li>{@link Kind#FILE} — everything from the marker line to the end of the file. Nothing needs to be
 *       matched, because a file marker claims the whole file.</li>
 *   <li>{@link Kind#MEMBER} and {@link Kind#BLOCK} — the braces of the declaration or statement below the
 *       marker. Both are found the same way (the next top-level <code>{</code>, then its matching
 *       <code>}</code>); they differ only in what the caller is being told the span is.</li>
 *   <li>{@link Kind#REGION} — the matching {@code region end <id>} line, so the span is the pair and
 *       everything between.</li>
 * </ul>
 *
 * @param kind      how this block's extent was determined
 * @param marker    the marker line that opened it
 * @param fromLine  the first line of the block, 1-based and inclusive; the marker's own line
 * @param toLine    the last line of the block, 1-based and inclusive
 * @param regionId  the pair id for a {@link Kind#REGION} block, otherwise {@code null}
 * @param closed    whether the extent was found. A block that is not closed is a broken marker, and it is
 *                  reported rather than silently extending to the end of the file
 * @param generator the generator's name from the marker payload, when the marker carries one
 */
public record GeneratedBlock(Kind kind, GeneratedCodeMarkers.Found marker,
                             int fromLine, int toLine, String regionId, boolean closed,
                             String generator) {

    /** How a block's extent was determined. */
    public enum Kind {

        /** A file marker: the block runs to the end of the file. */
        FILE("file"),

        /** A member marker: the declaration below it and its body. */
        MEMBER("member"),

        /** A region pair: the {@code begin} line through the matching {@code end} line. */
        REGION("region"),

        /** A block marker: the statement below it, bounded by the statement's own braces. */
        BLOCK("block");

        private final String scope;

        Kind(String scope) {
            this.scope = scope;
        }

        /** The marker scope word that produces this kind. */
        public String scope() {
            return scope;
        }

        /** The kind for a marker scope word, if this vocabulary defines one. */
        public static Optional<Kind> of(String scope) {
            for (Kind kind : values()) {
                if (kind.scope.equals(scope)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** How many lines the span covers. */
    public int lineCount() {
        return toLine - fromLine + 1;
    }

    /** Whether {@code line} (1-based) is inside this block's span. */
    public boolean containsLine(int line) {
        return line >= fromLine && line <= toLine;
    }

    /** A short description, for a report. */
    public String describe(String file) {
        return kind.scope() + " " + file + ":" + fromLine + "-" + toLine
                + (regionId == null ? "" : " (region " + regionId + ")")
                + (closed ? "" : " [UNCLOSED: " + marker.label() + " at line " + marker.line() + "]");
    }
}
