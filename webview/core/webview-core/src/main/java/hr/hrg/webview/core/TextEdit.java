package hr.hrg.webview.core;

/**
 * One replacement in a file, addressed the way a rendered page can address it: one-based line and column,
 * inclusive start, exclusive end.
 *
 * <p>Deliberately not a byte offset. A page renders text it received from {@code /file/} and knows which line
 * a reader clicked; asking it to compute UTF-8 byte offsets through a decoding it did not do is how edits land
 * in the wrong place. The conversion — line and column to offset — happens here, against the same bytes the
 * digest covers.
 *
 * <p>The end position is exclusive so that an insertion is expressible without a special case: start equals
 * end, and {@code newText} goes in there.
 */
public record TextEdit(int startLine, int startColumn, int endLine, int endColumn, String newText) {

    public TextEdit {
        if (startLine < 1 || startColumn < 1 || endLine < 1 || endColumn < 1) {
            throw new IllegalArgumentException("line and column are one-based: "
                    + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn);
        }
        if (newText == null) {
            throw new IllegalArgumentException("newText must be present; use an empty string to delete");
        }
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            throw new IllegalArgumentException("an edit cannot end before it starts: "
                    + startLine + ":" + startColumn + "-" + endLine + ":" + endColumn);
        }
    }

    /** An insertion at a position. */
    public static TextEdit insert(int line, int column, String text) {
        return new TextEdit(line, column, line, column, text);
    }

    /** A deletion of a range. */
    public static TextEdit delete(int startLine, int startColumn, int endLine, int endColumn) {
        return new TextEdit(startLine, startColumn, endLine, endColumn, "");
    }

    /**
     * A whole-line replacement between two one-based line numbers, inclusive.
     *
     * <p>The newline after the replaced lines belongs to the range, so the replacement has to carry one: when
     * {@code text} does not end with a newline it gets one appended. Without that, replacing line 2 of
     * {@code one\ntwo\nthree} with {@code TWO} produced {@code one\nTWOthree} — the terminator was consumed by
     * the range and never given back, which is a silent join of two lines rather than a replacement. Use
     * {@link #delete} to remove lines outright and {@link #insert} for partial-line work.
     */
    public static TextEdit lines(int fromLine, int toLine, String text) {
        String replacement = text.isEmpty() || text.endsWith("\n") ? text : text + "\n";
        return new TextEdit(fromLine, 1, toLine + 1, 1, replacement);
    }

    /** True when this edit changes nothing, which the caller may report rather than write. */
    public boolean isEmpty() {
        return newText.isEmpty() && startLine == endLine && startColumn == endColumn;
    }
}
