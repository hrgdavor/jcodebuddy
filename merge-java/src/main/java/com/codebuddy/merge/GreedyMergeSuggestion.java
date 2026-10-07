// {@link com.codebuddy.merge.GreedyMergeSuggestion} The greedy word-level pass, offered as a suggestion.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import com.codebuddy.merge.jetbrains.text.DiffRange;
import com.codebuddy.merge.jetbrains.text.TextCompare;
import com.codebuddy.merge.jetbrains.text.TextLines;

import java.util.List;
import java.util.Optional;

/**
 * The ported <b>greedy</b> merge pass — upstream's {@code tryGreedyResolve} (R3) with its unconditional deletion
 * application (R4) and its whitespace retry (R2) — delivered as a <b>suggestion</b>, never as an automatic answer
 * (unified plan step 4.17, {@code JETBRAINS_PORT.md} § 5.1–5.2 and § 6.4).
 *
 * <h2>Why this is a suggestion and not an automatic answer</h2>
 *
 * <p>The pass is two things wearing one name, and reading it as one is the mistake the port document warns
 * about. Its <b>composition</b> is mechanical; its <b>scope</b> is a trade upstream states in its own source:
 * <em>"we assume, that resolve results are explicitly verified by user and can be safely undone. Thus we trade
 * higher chances of incorrect resolve for higher chances of correct resolve."</em> A deletion is applied
 * unconditionally — neither side may have intended it — and applying that with nobody watching is exactly the
 * invisible regression {@code DESIGN_NEVER_AUTO_RESOLVED.md} § 5.3 exists to prevent. So the result is offered
 * with {@link Suggestion.Confidence#PLAUSIBLE}, and the trade is in the explanation rather than in a comment.
 *
 * <h2>The pass, exactly</h2>
 *
 * <p>It does <b>not</b> use a three-way comparison. It computes two independent two-way diffs
 * ({@code base→ours}, {@code base→theirs}), walks them, and collects each maximal run of changes that overlap in
 * base coordinates. For each such run:
 *
 * <table>
 *   <caption>What one run of changes produces</caption>
 *   <tr><th>ours</th><th>theirs</th><th>result</th></tr>
 *   <tr><td>empty</td><td>empty</td><td>nothing</td></tr>
 *   <tr><td>content</td><td>empty</td><td>our insertion</td></tr>
 *   <tr><td>empty</td><td>content</td><td>their insertion</td></tr>
 *   <tr><td>content</td><td>content, policy-equal</td><td>the <b>shorter</b></td></tr>
 *   <tr><td>content</td><td>content, not equal</td><td><b>refuse</b> — the ordering decision is a person's</td></tr>
 * </table>
 *
 * <p>Base lines inside a run are <b>not</b> emitted: that is R4, the unconditional deletion, and it is the reason
 * for the confidence this produces.
 *
 * <h2>What it records</h2>
 *
 * <p>The provenance names <b>the pass and the policy it ran under</b>, because the policy is a parameter and not a
 * global (upstream's switch is {@code DiffConfig.USE_GREEDY_MERGE_MAGIC_RESOLVE}, and a mutable global is
 * something this module must not port). A retry under {@link ComparisonPolicy#IGNORE_WHITESPACES} is therefore a
 * visibly different provenance from the first attempt, which is what lets a reviewer refuse one without refusing
 * the other.
 */
public final class GreedyMergeSuggestion {

    /** The pass, named as a reader would see it. The policy is appended to make the provenance complete. */
    public static final String PASS = "greedy word-level merge";

    private GreedyMergeSuggestion() {
    }

    /**
     * Run the greedy pass under {@code policy}, or return empty when it refuses.
     *
     * @param base   the block's base side; the two diffs are both taken against it
     * @param left   ours
     * @param right  theirs
     * @param policy the comparison policy, recorded on the suggestion rather than assumed
     */
    public static Optional<Suggestion> greedy(String base, String left, String right,
                                              ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        List<String> baseLines = TextLines.of(base == null ? "" : base).lines();
        List<String> leftLines = TextLines.of(left == null ? "" : left).lines();
        List<String> rightLines = TextLines.of(right == null ? "" : right).lines();
        String baseText = base == null ? "" : base;
        String leftText = left == null ? "" : left;
        String rightText = right == null ? "" : right;

        List<DiffRange> leftChanges = TextCompare.compareLines(baseText, leftText, effective);
        List<DiffRange> rightChanges = TextCompare.compareLines(baseText, rightText, effective);

        StringBuilder merged = new StringBuilder();
        int baseAt = 0;
        int leftAt = 0;
        int rightAt = 0;
        int leftIndex = 0;
        int rightIndex = 0;

        while (leftIndex < leftChanges.size() || rightIndex < rightChanges.size()) {
            DiffRange nextLeft = leftIndex < leftChanges.size() ? leftChanges.get(leftIndex) : null;
            DiffRange nextRight = rightIndex < rightChanges.size() ? rightChanges.get(rightIndex) : null;
            int nextBase = nextLeft == null ? nextRight.start1()
                : nextRight == null ? nextLeft.start1()
                : Math.min(nextLeft.start1(), nextRight.start1());

            // Everything before the next change is untouched on both sides, and is taken from the base - which is
            // also what keeps the composition from reordering code (the trap SideUnion exists for).
            append(merged, baseLines, baseAt, nextBase);
            leftAt += nextBase - baseAt;
            rightAt += nextBase - baseAt;
            baseAt = nextBase;

            // One run: every change on either side that overlaps in base coordinates.
            int baseStart = baseAt;
            int baseEnd = baseAt;
            int leftLength = 0;
            int rightLength = 0;
            boolean leftChanged = false;
            boolean rightChanged = false;
            while (leftIndex < leftChanges.size() && leftChanges.get(leftIndex).start1() <= baseEnd) {
                DiffRange change = leftChanges.get(leftIndex++);
                baseEnd = Math.max(baseEnd, change.end1());
                leftLength += change.length2();
                leftChanged = true;
            }
            while (rightIndex < rightChanges.size() && rightChanges.get(rightIndex).start1() <= baseEnd) {
                DiffRange change = rightChanges.get(rightIndex++);
                baseEnd = Math.max(baseEnd, change.end1());
                rightLength += change.length2();
                rightChanged = true;
            }
            int baseLength = baseEnd - baseStart;

            String ourText = joined(leftLines, leftAt, leftAt + leftLength);
            String theirText = joined(rightLines, rightAt, rightAt + rightLength);
            if (leftLength == 0 && rightLength == 0) {
                // Neither side inserted anything here, so this run is a pure deletion: **the base lines are
                // dropped**, which is R4's trade. An earlier version returned early here, and the deletion it was
                // supposed to apply was simply re-emitted from the tail — the pass looking like it worked while
                // doing the opposite of what it documents.
            } else if (rightLength == 0) {
                merged.append(ourText);
            } else if (leftLength == 0) {
                merged.append(theirText);
            } else if (policyEqual(ourText, theirText, effective)) {
                merged.append(ourText.length() <= theirText.length() ? ourText : theirText);
            } else {
                // R6: two different insertions at one point. No order is more correct, and that is a person's
                // decision rather than a pass's guess.
                return Optional.empty();
            }
            // R4, the trade: base lines inside the run are dropped - on a pure deletion that is the whole point of
            // the run, and on a replacement it is what "greedy" means.
            //
            // The cursors are the subtle half. A side that **changed** inside the run advances by its own text for
            // the run; a side that did not change **kept the run's base lines**, so it advances by the run's base
            // length. Advancing both by their own changed length is the bug this code carried: with one side
            // unchanged in a run, its cursor fell behind the base lines it still had, and every later slice of that
            // side was read from the wrong offset.
            baseAt = baseEnd;
            leftAt += leftChanged ? leftLength : baseLength;
            rightAt += rightChanged ? rightLength : baseLength;
        }
        append(merged, baseLines, baseAt, baseLines.size());

        String code = merged.toString();
        return Optional.of(new Suggestion(code,
            "Composed from two independent two-sided diffs, applying both sides' changes and dropping the base"
                + " lines inside them. That last part is a trade rather than a proof, which is why this is"
                + " offered instead of applied.",
            provenanceFor(effective),
            // TEXT_LOCAL, and deliberately not TEXT_INTRALINE: this walk compares whole LINES under a policy-aware
            // equality, so it can say which line differs but not which part of it. Recording the intra-line level
            // here would be an overclaim of exactly the kind this module keeps finding, and the honest record is
            // what a reviewer uses to judge the answer. Reaching TEXT_INTRALINE needs the word-level half of the
            // port (JETBRAINS_PORT.md § 6.5-6.6) and is named as step 4.11's remainder.
            AnalysisLevel.TEXT_LOCAL,
            List.of("deletions are applied unconditionally, so a line neither branch meant to drop can be gone",
                "compared line by line under " + effective + ", not word by word"),
            ConflictResolution.Verification.NOT_RUN,
            "",
            Suggestion.Confidence.PLAUSIBLE));
    }

    /**
     * The pass, retried under {@link ComparisonPolicy#IGNORE_WHITESPACES} when the first attempt refuses (R2).
     *
     * <p>Upstream retries under the lenient policy and <b>records nothing about the retry</b> — a reviewer could
     * not tell that a formatting difference had been swallowed. Here the retry is a second suggestion with its own
     * provenance naming the policy, so the two are separately refusable: refusing "the greedy pass under DEFAULT"
     * must not refuse "the greedy pass under IGNORE_WHITESPACES", because the second is a claim about whitespace
     * being churn that the first never made.
     */
    public static Optional<Suggestion> withWhitespaceRetry(String base, String left, String right,
                                                           ComparisonPolicy policy) {
        ComparisonPolicy effective = policy == null ? ComparisonPolicy.DEFAULT : policy;
        Optional<Suggestion> first = greedy(base, left, right, effective);
        if (first.isPresent() || effective == ComparisonPolicy.IGNORE_WHITESPACES) {
            return first;
        }
        return greedy(base, left, right, ComparisonPolicy.IGNORE_WHITESPACES);
    }

    /** The provenance: the pass and the policy, so a refusal can be specific to both. */
    public static String provenanceFor(ComparisonPolicy policy) {
        return PASS + " under " + (policy == null ? ComparisonPolicy.DEFAULT : policy);
    }

    private static boolean policyEqual(String left, String right, ComparisonPolicy policy) {
        List<String> leftLines = TextLines.of(left).lines();
        List<String> rightLines = TextLines.of(right).lines();
        if (leftLines.size() != rightLines.size()) {
            return false;
        }
        for (int index = 0; index < leftLines.size(); index++) {
            if (!policy.normaliseLine(leftLines.get(index))
                .equals(policy.normaliseLine(rightLines.get(index)))) {
                return false;
            }
        }
        return true;
    }

    private static String joined(List<String> lines, int from, int to) {
        StringBuilder text = new StringBuilder();
        append(text, lines, from, to);
        return text.toString();
    }

    /**
     * Append a stretch of lines verbatim.
     *
     * <p>Two conventions of {@link TextLines} decide this method, and both have caught this module before:
     * lines <b>carry their terminators</b> (so adding one would double every line break), and a text ending with
     * a terminator leaves an <b>empty final line</b> so that splitting and joining are inverses (so emitting that
     * empty line as a newline would add a blank line to the composed result). A real blank line is {@code "\n"}
     * and not empty, which is what makes "an empty line contributes nothing" exactly right.
     */
    private static void append(StringBuilder text, List<String> lines, int from, int to) {
        for (int line = from; line < to && line < lines.size(); line++) {
            String value = lines.get(line);
            if (value.isEmpty()) {
                // The trailing artifact of a text that ended with a terminator: it is the absence of a line, not
                // a line. Emitting it would append a newline the inputs never had.
                continue;
            }
            text.append(value);
            if (!value.endsWith("\n")) {
                text.append('\n');
            }
        }
    }
}
