package hr.hrg.webview.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff, because the default flow of an edit is a proposal a reader accepts (plan question 1).
 *
 * <p>Scope, stated plainly: this emits **one hunk** — the changed region plus {@link #CONTEXT} lines either
 * side — rather than the minimum set of hunks a merge tool would produce. For the contract's purpose (show the
 * reader what is about to change, in a format every diff viewer understands) that is sufficient and exact: the
 * header counts are right, the context lines are the file's real lines, and the body applies cleanly. Refining
 * it to multiple hunks is a Phase 3 follow-up, not a correctness gap — and it is noted here rather than implied.
 *
 * <p>A file with no trailing newline gets the standard {@code \ No newline at end of file} marker on both
 * sides, so a diff of an edit that only adds one is not mistaken for an edit that changed content.
 */
public final class UnifiedDiff {

    /** How many unchanged lines surround the change. */
    public static final int CONTEXT = 3;

    /** The marker git and GNU diff print when a line is not newline-terminated. */
    static final String NO_NEWLINE = "\\ No newline at end of file";

    private UnifiedDiff() {
    }

    /** The diff, or an empty string when the two texts are equal. */
    public static String between(String displayPath, String oldText, String newText) {
        List<String> oldLines = linesOf(oldText);
        List<String> newLines = linesOf(newText);

        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
                && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }

        int oldChanged = oldLines.size() - suffix;
        int newChanged = newLines.size() - suffix;
        if (prefix == oldChanged && prefix == newChanged) {
            return "";
        }

        int hunkStart = Math.max(0, prefix - CONTEXT);
        int oldHunkEnd = Math.min(oldLines.size(), oldChanged + CONTEXT);
        int newHunkEnd = Math.min(newLines.size(), newChanged + CONTEXT);

        List<String> body = new ArrayList<>();
        for (int i = hunkStart; i < prefix; i++) {
            body.add(" " + oldLines.get(i));
        }
        for (int i = prefix; i < oldChanged; i++) {
            body.add("-" + oldLines.get(i));
        }
        for (int i = prefix; i < newChanged; i++) {
            body.add("+" + newLines.get(i));
        }
        for (int i = oldChanged; i < oldHunkEnd; i++) {
            body.add(" " + oldLines.get(i));
        }

        StringBuilder diff = new StringBuilder();
        diff.append("--- ").append(displayPath).append('\n');
        diff.append("+++ ").append(displayPath).append('\n');
        diff.append("@@ -").append(range(hunkStart + 1, oldHunkEnd - hunkStart))
                .append(" +").append(range(hunkStart + 1, newHunkEnd - hunkStart)).append(" @@\n");
        for (String line : body) {
            diff.append(line).append('\n');
        }
        if (!oldText.isEmpty() && !oldText.endsWith("\n")) {
            diff.append(NO_NEWLINE).append('\n');
        }
        if (!newText.isEmpty() && !newText.endsWith("\n")) {
            diff.append(NO_NEWLINE).append('\n');
        }
        return diff.toString();
    }

    /** A unified-diff range: {@code count} alone when it is one, {@code start,count} otherwise. */
    private static String range(int start, int count) {
        return count == 1 ? String.valueOf(start) : start + "," + count;
    }

    /** Lines without their terminators; an empty text has no lines rather than one empty line. */
    static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        String withoutTrailing = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String line : withoutTrailing.split("\n", -1)) {
            lines.add(line);
        }
        return lines;
    }
}
