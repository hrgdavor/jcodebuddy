// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/comparison/ComparisonManagerImpl.kt, the mergeLines/compareLines entry points).
// @derived Rewritten in Java as a small static facade: this module needs two operations, not the manager's interface hierarchy.
// {enabled:true, blockMarker: "implicit"} The two-pass comparison: lines first, then words inside a changed line.
package com.codebuddy.merge.jetbrains.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The two-pass comparison: a line diff, then a word diff inside the changed line pairs.
 *
 * <h2>Why two passes</h2>
 *
 * <p>A line diff alone is too coarse to merge with. It can say "both sides changed this line" but not
 * whether they changed the <em>same</em> part of it, so two branches that edited different words of one
 * line are indistinguishable from two branches that rewrote it — which is the class of false conflict
 * this module is worst at, and the reason upstream's engine has a second pass at all.
 *
 * <p>So a changed line pair is compared again, at word granularity, and the fragments it yields are what
 * a later step uses to decide whether two edits interfere.
 *
 * <h2>The word-boundary rule</h2>
 *
 * <p>Taken from upstream's {@code ByWordRt}: a change region is extended outward so that it does not cut
 * a word in half. Two texts that differ in the middle of a word are reported as differing in the whole
 * word, because "the change starts at character 7" is not a fact a person or a merge rule can use, while
 * "the word {@code retries} became {@code retryCount}" is. The rule is applied per side independently,
 * and extending is bounded by the neighbouring fragment so two fragments cannot overlap.
 *
 * <p><b>What this does not do:</b> it is not upstream's {@code ByWordRt}, which uses a weighted search
 * tuned for highlighting. The fragments are word-aligned, but a pathological pair of long similar lines
 * may be split differently than upstream would split them. That is a known and deliberate difference —
 * recorded in {@code docs/JETBRAINS_PORT.md} § 3.2 — because the alternative was importing the platform.
 */
public final class TextCompare {

    private TextCompare() {
    }

    /**
     * Compare two texts by line.
     *
     * @return the changed line ranges, in order; empty when the two texts are equal under the policy
     */
    public static List<DiffRange> compareLines(String text1, String text2, ComparisonPolicy policy) {
        return compareLines(text1, text2, policy, MyersDiff.DEFAULT_MAX_CELLS);
    }

    /**
     * Compare two texts by line, with an explicit bound on the comparison's size.
     *
     * @throws DiffTooBigException when the comparison would need a table larger than {@code maxCells}
     */
    public static List<DiffRange> compareLines(String text1, String text2, ComparisonPolicy policy,
                                               long maxCells) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        TextLines lines1 = TextLines.of(text1);
        TextLines lines2 = TextLines.of(text2);
        return MyersDiff.diff(lines1.size(), lines2.size(),
            (index1, index2) -> lines1.normalised(index1, effective)
                .equals(lines2.normalised(index2, effective)),
            maxCells);
    }

    /**
     * The tokens of one line, in order, each with its own text.
     *
     * <p>Exposed because a word-level three-way composition needs the tokens themselves rather than the character
     * offsets {@link #compareWords} reports — and because the token boundary rule (a word is a run of letters, digits
     * or underscores; everything else is a one-character token) must exist in <b>one</b> place. A second tokenizer
     * written beside this one would agree with it until the day it did not, and the disagreement would show up as a
     * composed line that quietly differs from the comparison that justified it.
     *
     * <p>The line's terminator is not a token: it belongs to the line, not to its content.
     *
     * @param line   the line, with or without a terminator
     * @param policy how the tokens are read; it decides which tokens <em>match</em>, not how they are cut
     */
    public static List<String> tokens(String line, ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        List<String> texts = new ArrayList<>();
        for (Token token : tokenize(strip(line), effective)) {
            texts.add(token.text());
        }
        return texts;
    }

    /**
     * Compare one pair of lines by word.
     *
     * <p>Only meaningful for a pair the line pass reported as changed; on equal lines it returns nothing,
     * which is the honest answer rather than an empty change.
     *
     * @param line1  the first line, usually with its terminator
     * @param line2  the second line
     * @param policy how strictly the words are compared
     * @return the changes inside the pair, in order; empty when the lines match under the policy
     */
    public static List<WordFragment> compareWords(String line1, String line2,
                                                  ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        String left = strip(line1);
        String right = strip(line2);

        // The token boundary is a character class, not a set of strings: a word is a run of letters,
        // digits or underscores, and everything else is a one-character token. That is what makes
        // "count" and "count2" different words while "count" and "count," share one.
        List<Token> tokens1 = tokenize(left, effective);
        List<Token> tokens2 = tokenize(right, effective);

        List<WordFragment> fragments = new ArrayList<>();
        for (DiffRange range : MyersDiff.diff(tokens1.size(), tokens2.size(),
            (index1, index2) -> tokens1.get(index1).matches(tokens2.get(index2), effective))) {
            fragments.add(new WordFragment(
                tokens1.get(range.start1()).start(),
                tokens1.get(range.end1() - 1).end(),
                tokens2.get(range.start2()).start(),
                tokens2.get(range.end2() - 1).end()));
        }
        return alignToWordBoundaries(fragments, left, right);
    }

    /** One token: its text span, with the characters it contributes to a comparison. */
    private record Token(int start, int end, String text) {

        boolean matches(Token other, ComparisonPolicy policy) {
            return switch (policy) {
                case DEFAULT, TRIM_WHITESPACES -> text.equals(other.text);
                case IGNORE_WHITESPACES -> stripWhitespace(text).equals(stripWhitespace(other.text));
            };
        }
    }

    private static List<Token> tokenize(String line, ComparisonPolicy policy) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (policy.isIgnored(c)) {
                // Whitespace is a token of its own rather than skipped: under DEFAULT it is significant,
                // and under IGNORE_WHITESPACES every whitespace token compares equal to every other, so
                // keeping them means the offsets stay real while the matching still ignores them.
                int start = i;
                while (i < line.length() && policy.isIgnored(line.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(start, i, line.substring(start, i)));
            } else if (isWordCharacter(c)) {
                int start = i;
                while (i < line.length() && isWordCharacter(line.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(start, i, line.substring(start, i)));
            } else {
                tokens.add(new Token(i, i + 1, line.substring(i, i + 1)));
                i++;
            }
        }
        return tokens;
    }

    private static boolean isWordCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String strip(String line) {
        return ComparisonPolicy.stripTerminator(line);
    }

    private static String stripWhitespace(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                out.append(text.charAt(i));
            }
        }
        return out.toString();
    }

    /**
     * Extend each fragment outward so that it does not cut a word in half, bounded by its neighbours.
     *
     * <p>Extending rather than shrinking, because the alternative is a fragment that names half a word
     * and a caller cannot tell which half. Two fragments are never extended into one another: the
     * bound is the previous fragment's end on each side.
     */
    private static List<WordFragment> alignToWordBoundaries(List<WordFragment> fragments,
                                                            String left, String right) {
        List<WordFragment> aligned = new ArrayList<>(fragments.size());
        int floor1 = 0;
        int floor2 = 0;
        for (int i = 0; i < fragments.size(); i++) {
            WordFragment fragment = fragments.get(i);
            int ceiling1 = i + 1 < fragments.size() ? fragments.get(i + 1).start1() : left.length();
            int ceiling2 = i + 1 < fragments.size() ? fragments.get(i + 1).start2() : right.length();

            int start1 = extendLeft(left, fragment.start1(), floor1);
            int end1 = extendRight(left, fragment.end1(), ceiling1);
            int start2 = extendLeft(right, fragment.start2(), floor2);
            int end2 = extendRight(right, fragment.end2(), ceiling2);

            // Extending can close a gap that made a side empty, so re-check rather than assume: a
            // fragment with both sides empty is not a change and the record refuses it.
            if (start1 != end1 || start2 != end2) {
                aligned.add(new WordFragment(start1, end1, start2, end2));
            }
            floor1 = end1;
            floor2 = end2;
        }
        return List.copyOf(aligned);
    }

    private static int extendLeft(String line, int offset, int floor) {
        int at = offset;
        while (at > floor && isWordCharacter(line.charAt(at - 1))) {
            at--;
        }
        return at;
    }

    private static int extendRight(String line, int offset, int ceiling) {
        int at = offset;
        while (at < ceiling && isWordCharacter(line.charAt(at))) {
            at++;
        }
        return at;
    }
}
