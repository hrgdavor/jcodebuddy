// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Utility to apply surgical edits to a string.
 */
public class CodeEditApplier {

    /**
     * Applies a list of edits to the source text.
     * Edits should be non-overlapping for predictable results.
     */
    public static String applyStyles(String source, List<CodeEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return source;
        }

        // Sort edits from end to start to avoid index shifting during replacement
        List<CodeEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(CodeEdit::startLine)
                .thenComparingInt(CodeEdit::startCol)
                .reversed());

        StringBuilder sb = new StringBuilder(source);

        for (CodeEdit edit : sorted) {
            int startIdx = getOffset(source, edit.startLine(), edit.startCol());
            int endIdx = getOffset(source, edit.endLine(), edit.endCol());

            if (startIdx != -1 && endIdx != -1 && startIdx <= endIdx) {
                // Java StringBuilder.replace end is exclusive, but our end is inclusive
                sb.replace(startIdx, endIdx + 1, edit.newText());
            }
        }

        return sb.toString();
    }

    private static int getOffset(String source, int line, int col) {
        int currentLine = 1;
        int currentPos = 0;

        while (currentLine < line && currentPos < source.length()) {
            if (source.charAt(currentPos) == '\n') {
                currentLine++;
            }
            currentPos++;
        }

        if (currentLine == line) {
            int offset = currentPos + col - 1;
            return Math.min(offset, source.length());
        }

        return -1;
    }
}
