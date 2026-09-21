// {@link com.codebuddy.merge.Region} A line range in the base version of a file.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A half-open line range in the <em>base</em> version of a file, used to tell
 * whether two conflicts touch overlapping or disjoint parts of the code.
 *
 * <p>Regions are what make a mixed merge useful. A file where one region is a
 * mechanical import addition and another is a structural change can have the
 * mechanical part applied and the rest left for review - but only if the two
 * regions are known to be disjoint. Without that, the safe subset cannot be
 * identified and the whole file is manual.
 *
 * <p>Lines are 1-based and inclusive of {@code startLine} and {@code endLine}.
 * An unknown region is represented by {@link #unknown()}, which never overlaps
 * and is never reported as independently applicable.
 *
 * @param startLine first line of the region, 1-based
 * @param endLine   last line of the region, 1-based and inclusive
 */
public record Region(int startLine, int endLine) {

    private static final Region UNKNOWN = new Region(-1, -1);

    public Region {
        if (startLine > endLine && startLine != -1) {
            throw new IllegalArgumentException(
                "startLine " + startLine + " must not exceed endLine " + endLine);
        }
    }

    /**
     * A region that is not known. Never overlaps anything and is never
     * independently applicable, so an unattributable conflict is treated
     * conservatively.
     */
    public static Region unknown() {
        return UNKNOWN;
    }

    /**
     * True when this region carries real line information.
     */
    public boolean isKnown() {
        return startLine > 0 && endLine >= startLine;
    }

    /**
     * True when the two regions share at least one line.
     *
     * <p>An unknown region reports {@code false} rather than throwing, but
     * callers deciding what is safe to apply must check {@link #isKnown()}
     * separately - see
     * {@link MergeConflictResolver.MergeReport#getIndependentlyApplicable()}.
     */
    public boolean overlaps(Region other) {
        if (other == null || !isKnown() || !other.isKnown()) {
            return false;
        }
        return startLine <= other.endLine && other.startLine <= endLine;
    }

    /**
     * The number of lines covered.
     */
    public int lineCount() {
        return isKnown() ? endLine - startLine + 1 : 0;
    }

    /**
     * The single line region containing a line.
     */
    public static Region line(int lineNumber) {
        return new Region(lineNumber, lineNumber);
    }

    /**
     * A region spanning from the first to the last line, in either order.
     */
    public static Region spanning(int firstLine, int lastLine) {
        return firstLine <= lastLine
            ? new Region(firstLine, lastLine)
            : new Region(lastLine, firstLine);
    }

    /**
     * Locate {@code needle} inside {@code haystack} and return the line range it
     * covers.
     *
     * <p>Whitespace is ignored when matching, because the two versions being
     * compared are routinely formatted differently. When the needle is not found
     * the region is {@link #unknown()}, which is the safe answer: the caller then
     * cannot treat the conflict as disjoint from anything.
     */
    public static Region of(String haystack, String needle) {
        Optional<int[]> span = find(haystack, needle);
        if (span.isEmpty()) {
            return UNKNOWN;
        }
        int[] offsets = span.get();
        return spanning(lineOf(haystack, offsets[0]), lineOf(haystack, offsets[1] - 1));
    }

    /**
     * Locate a code fragment, ignoring whitespace runs, and return its
     * {@code [startOffset, endOffsetExclusive)} in the haystack.
     */
    static Optional<int[]> find(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = Pattern.compile(whitespaceInsensitivePattern(needle)).matcher(haystack);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new int[] {matcher.start(), matcher.end()});
    }

    /**
     * Build a regex that matches the needle while ignoring whitespace on either
     * side of the comparison.
     *
     * <p>Whitespace is allowed between <em>every</em> pair of characters, not
     * only where the needle itself has whitespace. The two versions being
     * compared are routinely formatted differently - one may write
     * {@code process(){audit();}} where the other writes
     * {@code process() { audit(); }} - and a region lookup that failed on that
     * would leave conflicts unattributed, which is the conservative answer but
     * needlessly so.
     */
    private static String whitespaceInsensitivePattern(String needle) {
        StringBuilder pattern = new StringBuilder();
        boolean first = true;
        for (int i = 0; i < needle.length(); i++) {
            char c = needle.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (!first) {
                pattern.append("\\s*");
            }
            pattern.append(Pattern.quote(String.valueOf(c)));
            first = false;
        }
        return pattern.toString();
    }

    /**
     * The 1-based line number containing a character offset.
     */
    static int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.min(offset, text.length() - 1);
        for (int i = 0; i <= limit && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    @Override
    public String toString() {
        return isKnown() ? "lines " + startLine + "-" + endLine : "unknown region";
    }
}
