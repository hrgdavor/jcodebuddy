package hr.hrg.webview.core;

import java.util.List;

/**
 * Where a {@link LocationFragment} landed in a file: one line, or a range, and how it was found.
 *
 * @param line    the first line, 1-based, as a page and a host both count them
 * @param endLine the last line of a selection; equal to {@code line} when the location is a single line
 * @param column  the column the caret lands on, 1-based; 1 unless the page named one (`#L42:12`)
 * @param kind    what kind of location it turned out to be
 * @param how     one short sentence naming what was matched, for a log line or a tooltip — a resolver that guesses
 *                silently is a resolver nobody can debug
 */
public record LocationResolution(int line, int endLine, int column, LocationFragment.Kind kind, String how) {

    public LocationResolution {
        if (line < 0) {
            throw new IllegalArgumentException("a line is 1-based and never negative: " + line);
        }
        if (endLine < line) {
            endLine = line;
        }
        if (column < 1) {
            column = 1;
        }
    }

    /** A location found by name or by key path, which lands at the start of its line. */
    public LocationResolution(int line, int endLine, LocationFragment.Kind kind, String how) {
        this(line, endLine, 1, kind, how);
    }

    /** True when the location selects more than one line. */
    public boolean isRange() {
        return endLine > line;
    }

    /** A short human description, for a host's log line. */
    public String summary() {
        return (isRange() ? "lines " + line + "\u2013" + endLine : "line " + line)
            + (column > 1 ? ", column " + column : "") + " (" + how + ")";
    }

    /** The lines of a file, 1-based by index, without the line terminators. */
    static List<String> lines(String text) {
        return List.of(text.split("\r\n|\r|\n", -1));
    }
}
