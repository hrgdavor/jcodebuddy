package hr.hrg.jcodebuddy.builder.edit;

import hr.hrg.jcodebuddy.builder.api.edit.CodeEdit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Applies surgical edits to a string.
 *
 * <p>Moved out of the frozen {@code java-watch-core} with {@link CodeEdit}; it is the builder's own applier, used by
 * the watch tools and the record-builder formatting tests. The frozen watcher keeps its copy for its own modules.</p>
 */
public final class CodeEditApplier {

    private CodeEditApplier() {
    }

    /**
     * Applies a list of edits to the source text. Edits should be non-overlapping for predictable results.
     */
    public static String applyStyles(String source, List<CodeEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return source;
        }

        // Sort edits from end to start to avoid index shifting during replacement.
        List<CodeEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(CodeEdit::startLine)
                .thenComparingInt(CodeEdit::startCol)
                .reversed());

        StringBuilder builder = new StringBuilder(source);

        for (CodeEdit edit : sorted) {
            int startIndex = offsetOf(source, edit.startLine(), edit.startCol());
            int endIndex = offsetOf(source, edit.endLine(), edit.endCol());

            if (startIndex != -1 && endIndex != -1 && startIndex <= endIndex) {
                // StringBuilder.replace's end is exclusive; the edit's end is inclusive.
                builder.replace(startIndex, endIndex + 1, edit.newText());
            }
        }

        return builder.toString();
    }

    private static int offsetOf(String source, int line, int column) {
        int currentLine = 1;
        int currentPosition = 0;

        while (currentLine < line && currentPosition < source.length()) {
            if (source.charAt(currentPosition) == '\n') {
                currentLine++;
            }
            currentPosition++;
        }

        if (currentLine == line) {
            int offset = currentPosition + column - 1;
            return Math.min(offset, source.length());
        }

        return -1;
    }
}
