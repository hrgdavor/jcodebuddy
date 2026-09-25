package hr.hrg.webview.core;

/**
 * A span of text in a file, with <b>one-based</b> line and column numbers, matching the frozen
 * contract's convention so nothing has to be converted at the boundary.
 *
 * <p>Line 1, column 1 is the start of the file. Hosts clamp: a page cannot know how long a file is, so
 * a line past the end is the last line rather than an error (contract § 7, item 4).
 */
public record TextRange(int startLine, int startColumn, int endLine, int endColumn) {

    public static TextRange at(int line, int column) {
        return new TextRange(line, column, line, column);
    }

    /** A range with every coordinate clamped to the first line/column at minimum. */
    public TextRange clamped() {
        return new TextRange(
                Math.max(1, startLine), Math.max(1, startColumn),
                Math.max(1, endLine), Math.max(1, endColumn));
    }
}
