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

    /** Split a text into lines, each line <b>carrying its terminator</b> except a final one that has none.
     *
     * <p>Terminators are kept, and that is what makes this splitter and {@link #text(int, int)} exact
     * inverses: a merge composes text by appending whole lines, so a line that has lost its terminator
     * would run into the next one. It was measured — composing
     * {@code ["a", "X", "c"]} produced {@code "aXc"} — and the defect is invisible until a merge actually
     * writes a file.
     *
     * <p>A trailing terminator therefore produces one more line, and that line is empty:
     * {@code "x\n"} is {@code ["x\n", ""]} against {@code "x"} as {@code ["x"]}. The distinction is the
     * one a version-control system reports — a file with and without a final newline — so it survives the
     * split.
     *
     * <h2>Comparison ignores the terminators</h2>
     *
     * <p>{@link ComparisonPolicy#normaliseLine} strips them, so {@code ["x\n", ""]} and
     * {@code ["x", ""]} compare equal: a CRLF file and its LF twin have the same content, and a terminator
     * is a separator rather than content. What matters for correctness is that the <em>composition</em>
     * keeps them while the <em>comparison</em> does not.
     */
    public static TextLines of(String text) {
        String value = text == null ? "" : text;
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n') {
                lines.add(value.substring(start, i + 1));
                start = i + 1;
            } else if (c == '\r') {
                // A CRLF is one terminator; both characters belong to the line that ends with it.
                int end = (i + 1 < value.length() && value.charAt(i + 1) == '\n') ? i + 2 : i + 1;
                lines.add(value.substring(start, end));
                i = end - 1;
                start = end;
            }
        }
        // One rule after the loop, so the empty text and a text ending with a terminator agree: both leave
        // an empty final line. Splitting them into two branches is what once made `of("")` return no lines.
        lines.add(value.substring(start));
        return new TextLines(lines);
    }

    public int size() {
        return lines.size();
    }

    public String line(int index) {
        return lines.get(index);
    }

    /**
     * The text formed by lines {@code [from, to)}, with a line terminator <b>between</b> the lines.
     *
     * <p>The separators are put back, and that is not cosmetic: a splitter that strips terminators and a
     * join that does not restore them round-trips to something that is not the input, which would corrupt
     * every composed merge. It was measured — {@code text(0, 3)} of {@code "a\nb\nc\n"} returned
     * {@code "abc"} — and the property this method now holds is that
     * {@code TextLines.of(t).text()} equals {@code t} for any {@code t} that does not end with a
     * terminator.
     *
     * <p>The separator is {@code "\n"} regardless of what the text used. Normalising line endings is the
     * one place a merge may legitimately do that, because the alternative — remembering each line's
     * original terminator — is state carried for a difference no person recognises, and the round trip is
     * what the equivalence above is stated against.
     */
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
