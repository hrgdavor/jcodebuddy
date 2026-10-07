// Licensed under the Apache License 2.0; see ../../../../../../../THIRD_PARTY_NOTICES.md — Copyright (C) JetBrains s.r.o.
// Derived from JetBrains/intellij-community at commit 9f5f034237b2f5ccdec336f1749b3e0bd1b1f7c5 (platform/util/diff/src/com/intellij/diff/util/MergeResolveUtil.kt).
// @derived Translated from Kotlin to Java: the word-level sub-composition the simple pass performs inside a changed range (GreedyHelper/IgnoringChangeBuilder territory), with this module's own two rules named where they are applied.
// {enabled:true, blockMarker: "implicit"} Three-way composition inside one conflicting line: words, not lines.
package com.codebuddy.merge.jetbrains.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.DiffRange;
import com.codebuddy.merge.jetbrains.text.MyersDiff;
import com.codebuddy.merge.jetbrains.text.TextCompare;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The <b>word-level</b> half of the port: composing three versions of <em>one line</em>, where the line-level pass
 * can only say that the lines differ (unified plan step 4.11's remainder, {@code JETBRAINS_PORT.md} § 11.2 and
 * § 6.5).
 *
 * <h2>Why this exists, in one case</h2>
 *
 * <p>The benchmark's most valuable resolve vectors are all of this shape: {@code y z | x y z | x y} resolves to
 * {@code y}, because <b>each side deleted a different word</b> and the word they both kept is in no disagreement. A
 * line comparison sees one line changed on both sides and refuses — correctly, by its own lights, because it has no
 * way to say <em>which part</em> differs. A word comparison can, and the answer is then mechanical: the words nobody
 * touched.
 *
 * <h2>How it composes, and why it is the same walk</h2>
 *
 * <p>Deliberately the <b>same algorithm</b> as the line-level pass, one granularity down: two independent two-way
 * diffs ({@code base→ours}, {@code base→theirs}), walked for maximal runs that overlap in base coordinates, each run
 * decided by the same table — one side changed takes that side, both changed identically takes either, both changed
 * differently <b>refuses</b>. The refusal rule (R6) is what keeps this honest: at word granularity "two different
 * insertions at one point" is <em>more</em> common, not less, and a pass that guessed an order would be inventing
 * text nobody wrote.
 *
 * <h2>Two rules learned the hard way, applied rather than rediscovered</h2>
 *
 * <ul>
 *   <li><b>A side that did not change has the base's tokens over a run</b>, not nothing. Reading a run's own empty
 *       extent as content has cost this project three defects already ({@code MergeRangeBuilder},
 *       {@code BlockComposition}, {@code ConflictShape}).</li>
 *   <li><b>Adjacent is not overlapping.</b> Runs are merged only when they genuinely overlap, or when two insertions
 *       sit at the same point and their order is therefore undecidable. An absorb that also merged merely
 *       <em>touching</em> changes is the defect the randomized property found at line level in round 30, and the
 *       same mistake here would refuse two independent word edits that a person would compose without thinking.</li>
 * </ul>
 *
 * <h2>The limit, stated rather than discovered</h2>
 *
 * <p>It composes <b>one line against one line against one line</b>. A range covering several lines is refused rather
 * than half-composed: word composition across line boundaries would have to decide where the line breaks belong, and
 * that decision is not in the text. The benchmark's vectors are all single-line; a caller needing more must bring a
 * vector that shows the answer.
 */
public final class WordLevelMerge {

    private WordLevelMerge() {
    }

    /**
     * Compose the three versions of one line, or refuse.
     *
     * @param basePart   the base's text for the range, one line
     * @param oursPart   our side's text for the range, one line
     * @param theirsPart their side's text for the range, one line
     * @return the composed line, carrying the base's own terminator, or empty when the three sides cannot be
     *         composed — including whenever any side spans more than one line
     */
    public static Optional<String> compose(String basePart, String oursPart, String theirsPart,
                                           ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        if (!isSingleLine(basePart) || !isSingleLine(oursPart) || !isSingleLine(theirsPart)) {
            return Optional.empty();
        }
        // All three sides must have something to compare WITHIN. When the base is empty the range is an insertion at
        // a point, and two insertions at one point are R6 - undecidable, one granularity up. Tokenising an empty side
        // to no tokens made "ours inserted an empty line, theirs inserted a word" look like "only theirs changed",
        // and the pass composed a word the benchmark calls a conflict: the change-type family caught it as an
        // application where the benchmark needs a person, which is the serious direction. A guard rather than a
        // heuristic, because the case is exactly statable.
        if (basePart.isBlank() || oursPart.isBlank() || theirsPart.isBlank()) {
            return Optional.empty();
        }
        List<String> base = TextCompare.tokens(basePart, effective);
        List<String> ours = TextCompare.tokens(oursPart, effective);
        List<String> theirs = TextCompare.tokens(theirsPart, effective);

        List<DiffRange> oursChanges = diff(base, ours, effective);
        List<DiffRange> theirsChanges = diff(base, theirs, effective);

        List<String> composed = new ArrayList<>();
        int baseAt = 0;
        int oursAt = 0;
        int theirsAt = 0;
        int oursIndex = 0;
        int theirsIndex = 0;

        while (oursIndex < oursChanges.size() || theirsIndex < theirsChanges.size()) {
            DiffRange nextOurs = oursIndex < oursChanges.size() ? oursChanges.get(oursIndex) : null;
            DiffRange nextTheirs = theirsIndex < theirsChanges.size() ? theirsChanges.get(theirsIndex) : null;
            int nextBase = nextOurs == null ? nextTheirs.start1()
                : nextTheirs == null ? nextOurs.start1()
                : Math.min(nextOurs.start1(), nextTheirs.start1());

            // Untouched by both sides, so it comes from the base — which is what keeps a composed line from
            // reordering the words around a change.
            int unchanged = nextBase - baseAt;
            for (int token = 0; token < unchanged; token++) {
                composed.add(base.get(baseAt + token));
            }
            oursAt += unchanged;
            theirsAt += unchanged;
            baseAt = nextBase;

            // One run: the changes on either side that belong together.
            int baseStart = baseAt;
            int baseEnd = baseAt;
            int oursInserted = 0;
            int theirsInserted = 0;
            boolean oursChanged = false;
            boolean theirsChanged = false;
            while (oursIndex < oursChanges.size() && belongsToRun(oursChanges.get(oursIndex), baseStart, baseEnd)) {
                DiffRange change = oursChanges.get(oursIndex++);
                baseEnd = Math.max(baseEnd, change.end1());
                oursInserted += change.length2();
                oursChanged = true;
            }
            while (theirsIndex < theirsChanges.size() && belongsToRun(theirsChanges.get(theirsIndex), baseStart, baseEnd)) {
                DiffRange change = theirsChanges.get(theirsIndex++);
                baseEnd = Math.max(baseEnd, change.end1());
                theirsInserted += change.length2();
                theirsChanged = true;
            }
            int baseLength = baseEnd - baseStart;
            if (baseLength == 0 && !oursChanged && !theirsChanged) {
                // A run that consumed nothing would leave the cursors where they are and spin forever. Refusing is
                // the safe answer and the honest one: the walk could not place the next change, and a composition
                // built on a walk that lost its place is worse than no composition. (The case that produced this
                // guard is in the javadoc of `belongsToRun`; a hang is the failure mode worth spending a branch on.)
                return Optional.empty();
            }

            if (!oursChanged) {
                // Ours kept these base tokens; theirs' own text for the run is what belongs here.
                composed.addAll(slice(theirs, theirsAt, theirsAt + theirsInserted));
            } else if (!theirsChanged) {
                composed.addAll(slice(ours, oursAt, oursAt + oursInserted));
            } else if (oursInserted == 0 && theirsInserted == 0) {
                // Both removed the base's tokens here: one deletion, and the tokens neither side kept are content
                // nobody wanted.
            } else {
                List<String> ourText = slice(ours, oursAt, oursAt + oursInserted);
                List<String> theirText = slice(theirs, theirsAt, theirsAt + theirsInserted);
                if (!sameTokens(ourText, theirText)) {
                    // R6 at word granularity: two different insertions at one point. No order is more correct, so
                    // the pass refuses rather than inventing one — the rule that makes a composed line a
                    // composition rather than a guess.
                    return Optional.empty();
                }
                composed.addAll(ourText);
            }
            baseAt = baseEnd;
            oursAt += oursChanged ? oursInserted : baseLength;
            theirsAt += theirsChanged ? theirsInserted : baseLength;
        }
        for (int token = baseAt; token < base.size(); token++) {
            composed.add(base.get(token));
        }

        StringBuilder text = new StringBuilder();
        composed.forEach(text::append);
        text.append(terminatorOf(basePart));
        return Optional.of(text.toString());
    }

    /**
     * Whether a change belongs to the run that starts at {@code baseStart} and currently reaches {@code baseEnd}.
     *
     * <p>Three ways in, and the first is the one whose absence hung a test run: <b>a change starting exactly at the
     * run's start always belongs to it</b>, because the run was positioned at that change. Without it, a plain
     * <em>replacement</em> at the run's start belonged to no run at all — it was neither before {@code baseEnd} nor
     * an insertion — so the run consumed nothing, the cursors did not move, and the walk looped forever. A hang is
     * the worst shape of this bug, which is why {@link #compose} also refuses rather than looping if a run ever
     * consumes nothing.
     *
     * <p>The other two: genuine <b>overlap</b>, and two <b>insertions at the same point</b>, where the order is
     * undecidable and they must be decided together. Mere <em>adjacency</em> is deliberately not enough — the
     * distinction the line-level builder learned the hard way in round 30.
     */
    private static boolean belongsToRun(DiffRange change, int baseStart, int baseEnd) {
        if (change.start1() == baseStart) {
            return true;
        }
        if (change.start1() < baseEnd) {
            return true;
        }
        boolean changeIsInsertion = change.start1() == change.end1();
        boolean runIsInsertion = baseStart == baseEnd;
        return changeIsInsertion && runIsInsertion && change.start1() == baseEnd;
    }

    /** True when a side is at most one line: no terminator anywhere but possibly at its very end. */
    private static boolean isSingleLine(String part) {
        String value = part == null ? "" : part;
        int end = value.length();
        if (value.endsWith("\n")) {
            end = value.endsWith("\r\n") ? value.length() - 2 : value.length() - 1;
        }
        return value.lastIndexOf('\n', Math.max(0, end - 1)) < 0;
    }

    /** The base's own terminator, so a composed line ends the way the line it replaces did. */
    private static String terminatorOf(String part) {
        if (part == null) {
            return "";
        }
        if (part.endsWith("\r\n")) {
            return "\r\n";
        }
        return part.endsWith("\n") ? "\n" : "";
    }

    private static List<DiffRange> diff(List<String> base, List<String> other, ComparisonPolicy policy) {
        return MyersDiff.diff(base.size(), other.size(),
            (index1, index2) -> policy.normaliseLine(base.get(index1))
                .equals(policy.normaliseLine(other.get(index2))));
    }

    private static List<String> slice(List<String> tokens, int from, int to) {
        int start = Math.max(0, Math.min(from, tokens.size()));
        int end = Math.max(start, Math.min(to, tokens.size()));
        return new ArrayList<>(tokens.subList(start, end));
    }

    /** Two token runs that mean the same thing: equal token for token. */
    private static boolean sameTokens(List<String> ours, List<String> theirs) {
        if (ours.size() != theirs.size()) {
            return false;
        }
        for (int index = 0; index < ours.size(); index++) {
            if (!ours.get(index).equals(theirs.get(index))) {
                return false;
            }
        }
        return true;
    }
}
