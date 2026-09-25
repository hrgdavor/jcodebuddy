package hr.hrg.webview.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A file's bytes turned into something edits can be addressed against, and back again without collateral change.
 *
 * <p>Three properties, each of which the write contract depends on:
 *
 * <ul>
 *   <li><b>Positions are line and column</b>, converted here against the same decoding the digest covers. A
 *       page knows which line a reader clicked; it does not know byte offsets.</li>
 *   <li><b>The file's line endings survive.</b> The text is held with {@code \n} internally and rendered back
 *       with whatever the file used. A CRLF file that is edited must still be a CRLF file — otherwise every
 *       edit would show up as a whole-file diff in the reader's version control.</li>
 *   <li><b>The shape of the end of the file is preserved</b>, because the trailing newline is part of the text
 *       rather than a flag beside it: a file that ended with one still does, and one that did not is not given
 *       a terminator behind the reader's back. An edit that genuinely appends a blank line adds one.</li>
 * </ul>
 *
 * <p>The line index follows the same rule: a text ending in {@code \n} has one more (empty) line position, so
 * "one past the last line" is addressable — which is how a caller appends at the end of a file without knowing
 * its length.
 */
public final class EditableText {

    private final String decoded;
    private final String normalized;
    private final String lineSeparator;
    private final int[] lineStarts;
    private final int[] lineLengths;

    private EditableText(String decoded, String normalized, String lineSeparator, int[] lineStarts,
                         int[] lineLengths) {
        this.decoded = decoded;
        this.normalized = normalized;
        this.lineSeparator = lineSeparator;
        this.lineStarts = lineStarts;
        this.lineLengths = lineLengths;
    }

    /**
     * Reads a file's bytes. The separator is the one that actually dominates the file rather than a guess from
     * the platform, because a file can arrive from anywhere.
     */
    public static EditableText of(byte[] bytes) {
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        boolean crlf = countOccurrences(decoded, "\r\n") * 2 >= countOccurrences(decoded, "\n");
        String normalized = decoded.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int[] starts = new int[lines.length];
        int[] lengths = new int[lines.length];
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            starts[i] = offset;
            lengths[i] = lines[i].length();
            offset += lines[i].length() + 1;
        }
        return new EditableText(decoded, normalized, crlf ? "\r\n" : "\n", starts, lengths);
    }

    /** The file as it was decoded, with its own separators. */
    public String original() {
        return decoded;
    }

    public String lineSeparator() {
        return lineSeparator;
    }

    /** True when the file ended with a line terminator; kept as a query because callers report it. */
    public boolean endedWithNewline() {
        return normalized.endsWith("\n");
    }

    /** How many line positions the text has; a text ending in {@code \n} has a final empty one. */
    public int lineCount() {
        return lineStarts.length;
    }

    /**
     * Applies edits to the text and returns the result, still with {@code \n} internally and still ending the
     * way the original did unless an edit changed that.
     *
     * @throws IllegalArgumentException when a position is outside the file or two edits overlap — an
     *         {@code INVALID_EDIT}, which the caller reports rather than half-applies
     */
    public String apply(List<TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            throw new IllegalArgumentException("no edits were supplied");
        }
        List<int[]> ranges = new ArrayList<>(edits.size());
        for (TextEdit edit : edits) {
            int start = offsetOf(edit.startLine(), edit.startColumn());
            int end = offsetOf(edit.endLine(), edit.endColumn());
            if (end < start) {
                throw new IllegalArgumentException("edit ends before it starts: " + describe(edit));
            }
            ranges.add(new int[] {start, end});
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> ranges.get(i)[0]).reversed());
        for (int i = 1; i < order.size(); i++) {
            int previous = order.get(i - 1);
            int current = order.get(i);
            if (ranges.get(current)[1] > ranges.get(previous)[0]) {
                throw new IllegalArgumentException("edits overlap: " + describe(edits.get(previous))
                        + " and " + describe(edits.get(current)));
            }
        }

        StringBuilder result = new StringBuilder(normalized);
        for (int index : order) {
            int[] range = ranges.get(index);
            result.replace(range[0], range[1], edits.get(index).newText());
        }
        return result.toString();
    }

    /** Turns edited text back into the bytes to write, restoring the file's line endings. */
    public byte[] render(String edited) {
        return ("\n".equals(lineSeparator) ? edited : edited.replace("\n", lineSeparator))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** The original text as a display string, i.e. with the file's own separators. */
    public String renderedOriginal() {
        return "\n".equals(lineSeparator) ? normalized : normalized.replace("\n", lineSeparator);
    }

    /** The text an edit produces, for display: what a diff is computed between. */
    public String renderedAfter(String edited) {
        return new String(render(edited), StandardCharsets.UTF_8);
    }

    /** The normalized text, for a test that wants to see the model rather than the rendering. */
    public String normalized() {
        return normalized;
    }

    private int offsetOf(int line, int column) {
        int index = line - 1;
        if (index < 0 || index >= lineStarts.length) {
            throw new IllegalArgumentException("line " + line + " is outside the file, which has "
                    + lineStarts.length + (lineStarts.length == 1 ? " line" : " lines"));
        }
        int columnIndex = column - 1;
        if (columnIndex < 0 || columnIndex > lineLengths[index]) {
            throw new IllegalArgumentException("column " + column + " is outside line " + line
                    + ", which has " + lineLengths[index] + " characters");
        }
        return lineStarts[index] + columnIndex;
    }

    private static String describe(TextEdit edit) {
        return edit.startLine() + ":" + edit.startColumn() + "-" + edit.endLine() + ":" + edit.endColumn();
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
