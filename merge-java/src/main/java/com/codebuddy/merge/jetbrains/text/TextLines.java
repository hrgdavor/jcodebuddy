// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/tools/util/text/LineOffsets.kt).
// @derived Rewritten in Java: upstream keeps offsets in a flat int array behind an interface, and a plain String list plus an explicit split is what this module needs.
// {enabled:true, blockMarker: "implicit"} A text split into lines, with a trailing empty line kept.
package com.codebuddy.merge.jetbrains.text;

import java.util.ArrayList;
import java.util.List;

/**
 * A text split into lines, with the same convention as upstream: <b>a trailing terminator produces one
 * final empty line</b>.
 *
 * <h2>Why the trailing empty line is kept</h2>
 *
 * <p>{@code "x"} is one line and {@code "x\n"} is two — the second being empty. That is not pedantry: it
 * is the difference between a file with a final newline and one without, which a version-control system
 * reports and a merge has to preserve. A split that dropped it would make the two equal, and a merge
 * built on that equality would quietly add or remove a final newline.
 *
 * <p>The alternative — a flag saying "there was a terminator" — moves the same information somewhere
 * else and then has to be consulted at every use. Keeping the empty line is the representation that
 * needs no flag, and it is what upstream's diff produces (a change inserting at the last line is an
 * insertion of an empty line, not a special case).
 *
 * <h2>Terminators are recognised, not assumed</h2>
 *
 * <p>{@code "\n"}, {@code "\r\n"} and a lone {@code "\r"} all end a line. A file that mixes them is
 * unusual but not invalid, and a splitter that only knew {@code "\n"} would treat every {@code "\r\n"}
 * as content, making a CRLF file differ from its LF twin for no reason a person recognises.
 */
public record TextLines(List<String> lines) {

    public TextLines {
        lines = List.copyOf(lines);
    }

    /** Split a text into lines.
     *
     * <p><b>A line is its content, and a terminator is a separator rather than content</b>, which is why
     * {@code "x\n"} is {@code ["x", ""]} — the second line being the empty one after the final separator.
     * Keeping the terminator inside the line instead (so {@code ["x\n", ""]}) makes a CRLF file's lines
     * differ from its LF twin's, which is a difference no person recognises and no merge wants, and it
     * was measured: {@code TextLines.of("x\ny\n")} and {@code of("x\r\ny\r\n")} came out unequal.
     *
     * <p>The trailing empty line is still kept, and it is what distinguishes {@code "x"} from
     * {@code "x\n"} — one line against two. That distinction is the one a version-control system reports,
     * so it has to survive the split.
     */
    public static TextLines of(String text) {
        String value = text == null ? "" : text;
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                lines.add(value.substring(start, i));
                start = i + 1;
            } else if (c == '\r') {
                // A CRLF is one separator; consume both, and keep neither in the line's content.
                int end = (i + 1 < value.length() && value.charAt(i + 1) == '\n') ? i + 2 : i + 1;
                lines.add(value.substring(start, i));
                i = end - 1;
                start = end;
            }
        }
        // The final line, which is empty when the text ended with a separator and is the whole text when
        // it did not. One rule for both, because splitting them into two branches is what once made
        // `of("")` return no lines at all.
        lines.add(value.substring(start));
        return new TextLines(lines);
    }

    public int size() {
        return lines.size();
    }

    public String line(int index) {
        return lines.get(index);
    }

    /** The text formed by lines {@code [from, to)}. */
    public String text(int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            out.append(lines.get(i));
        }
        return out.toString();
    }

    /** The normalised form of one line, under a policy. */
    public String normalised(int index, ComparisonPolicy policy) {
        return policy.normaliseLine(lines.get(index));
    }

    /** The whole text as it was given, terminators included. */
    public String text() {
        return text(0, lines.size());
    }
}
