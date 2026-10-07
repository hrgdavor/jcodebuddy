// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/ComparisonPolicy.kt).
// @derived Translated from Kotlin to Java: the same three constants, with the semantics of each written out as javadoc.
// {enabled:true, blockMarker: "implicit"} How strictly two texts are compared.
package com.codebuddy.merge.jetbrains.text;

/**
 * How strictly two texts are compared. The upstream file this derives from is named once, in the header
 * above — a derived file states the one file it came from, and naming it again here would look like a
 * second source.
 *
 * <h2>Two policies that are not one policy</h2>
 *
 * <p>The distinction that matters, and the one this module did not have before: <b>trimming and ignoring
 * whitespace are different operations</b>. Conflict detection used to compare {@code line.trim()} inside
 * a {@code LinkedHashSet}, which conflates them — it ignores each line's edges *and* matches the line
 * anywhere in the file, which is a third thing again.
 *
 * <ul>
 *   <li>{@link #TRIM_WHITESPACES} — a line's <b>leading and trailing</b> whitespace is ignored. Interior
 *       whitespace still counts, so {@code "x  y"} and {@code "x y"} differ. This is what a person means
 *       by "the indentation changed".</li>
 *   <li>{@link #IGNORE_WHITESPACES} — <b>all</b> whitespace is ignored, so {@code "x  y"} and
 *       {@code "xy"} compare equal. This is what a person means by "it is the same token stream".</li>
 * </ul>
 *
 * <p>Neither is "the lenient one": a merge that ignores interior whitespace will happily join two
 * tokens that were separate, which is why the choice is the caller's and is recorded on whatever the
 * caller produced. {@link #DEFAULT} compares exactly, apart from line terminators, which are not part of
 * a line's content.
 */
public enum ComparisonPolicy {

    /**
     * Exact comparison, except that a line's terminator is not part of its content.
     *
     * <p>So {@code "x\n"} and {@code "x"} have the same single line, while {@code "x "} and {@code "x"}
     * are different lines. This is the policy a caller gets when it does not choose one, because it is
     * the only one that invents nothing.
     */
    DEFAULT,

    /**
     * Like {@link #DEFAULT}, but a line's leading and trailing whitespace is ignored.
     *
     * <p>Interior whitespace still counts. A line that is only whitespace compares equal to an empty
     * line, which is what makes re-indentation stop being a change.
     */
    TRIM_WHITESPACES,

    /**
     * All whitespace is ignored, inside a line as well as at its edges.
     *
     * <p>The strictest thing this policy can do is make two token streams equal that were spelled
     * differently, so a caller that chooses it is saying formatting carries no meaning here.
     */
    IGNORE_WHITESPACES;

    /**
     * True when {@code character} is whitespace for the purposes of this policy.
     *
     * <p>Uses {@link Character#isWhitespace(char)} rather than a hand-written set: the same code must
     * treat a tab, a non-breaking space and a line separator consistently, and a hand-written set is a
     * place for exactly one of them to be forgotten.
     */
    public boolean isIgnored(char character) {
        return this == IGNORE_WHITESPACES && Character.isWhitespace(character);
    }

    /**
     * Normalise one line for comparison under this policy.
     *
     * <p>Returns the text that is compared, not the text that is kept: a caller that applies a resolution
     * writes the <b>original</b> text, never this. That distinction is why the policies live here as a
     * comparison concern rather than as a rewriting step.
     */
    public String normaliseLine(String line) {
        String withoutTerminator = stripTerminator(line);
        return switch (this) {
            case DEFAULT -> withoutTerminator;
            case TRIM_WHITESPACES -> withoutTerminator.strip();
            case IGNORE_WHITESPACES -> removeAllWhitespace(withoutTerminator);
        };
    }

    /**
     * Remove a trailing line terminator, which is a separator rather than content.
     *
     * <p>{@code "\r\n"} is removed whole: treating it as two characters would make every CRLF file differ
     * from its LF twin for no reason a person recognises.
     */
    static String stripTerminator(String line) {
        if (line.isEmpty()) {
            return line;
        }
        int end = line.length();
        if (line.charAt(end - 1) == '\n') {
            end--;
            if (end > 0 && line.charAt(end - 1) == '\r') {
                end--;
            }
        } else if (line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }

    private static String removeAllWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                out.append(text.charAt(i));
            }
        }
        return out.toString();
    }
}
