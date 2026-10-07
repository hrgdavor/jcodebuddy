// {@link com.codebuddy.merge.MethodBodyChangeConflictResolver} Resolves compatible method body changes.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.merge.MergeResolve;
import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves conflicts where both branches edited the body of the same method.
 *
 * Many of these are false conflicts: the two edits touch different statements
 * and can be combined mechanically. This resolver detects that case and offers
 * the combined body, still as a {@code REVIEW} result, because "the edits look
 * independent" is a syntactic judgement and only a human can confirm the merged
 * behaviour is what was intended.
 *
 * When the edits overlap, the resolver refuses and describes both sides.
 */
public final class MethodBodyChangeConflictResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.METHOD_BODY_CHANGE;
    }

    /**
     * {@link AnalysisLevel#TEXT_INTRALINE}, and that is a rise from {@code TEXT_LOCAL} which the
     * {@linkplain #composedAtWordLevel intra-line path} earned (plan step 4.11's declaration half).
     *
     * <p>The declaration says what this resolver can <b>read</b>, and it now reads one level finer than a line
     * set: when the statement sets cannot be combined, it asks the ported resolve pass, which composes the words
     * <em>inside</em> a changed line. It still reads nothing outside the block — no import block, no declaration
     * three lines up — which is what keeps it below {@code TEXT_FILE}.
     *
     * <p>Both levels are reachable, and the recorded level says which happened: a composition that needed the word
     * comparison records {@code TEXT_INTRALINE}, and one the line comparison alone reached records
     * {@code TEXT_LOCAL}. A resolver that declared the finer level and recorded it for every answer would be
     * claiming a reading it did not do, which is the failure {@code AnalysisLevel} exists to make visible.
     */
    @Override
    public AnalysisLevel maxAnalysisLevel() {
        return AnalysisLevel.TEXT_INTRALINE;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        List<String> body1 = statementsIn(conflict.getBranch1Code());
        List<String> body2 = statementsIn(conflict.getBranch2Code());
        List<String> baseBody = statementsIn(conflict.getBaseCode());

        if (body1.isEmpty() || body2.isEmpty()) {
            // A side with no statements at all is not a body edit to combine — but it may still be a change the
            // ported pass can compose (a one-sided edit, or words inside a line), so it falls through to the
            // intra-line path rather than being dismissed here. That path returns null when the pass refuses, which
            // is the same outcome this branch used to produce.
            return composedBeyondStatementSets(conflict);
        }

        Set<String> changedByBranch1 = difference(body1, baseBody);
        Set<String> changedByBranch2 = difference(body2, baseBody);

        Set<String> overlap = new LinkedHashSet<>(changedByBranch1);
        overlap.retainAll(changedByBranch2);

        // Same edit on both sides: nothing to reconcile.
        if (!overlap.isEmpty() && changedByBranch1.equals(changedByBranch2)) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .analysisLevel(AnalysisLevel.TEXT_LOCAL)
                .explanation("Both branches made the same " + overlap.size()
                    + " edit(s), so either side's body is already correct.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        // The intra-line path is tried BEFORE the statement-set branches, and a measurement is what moved it here.
        // The statement comparison works on whole LINES, so two branches that edited the SAME line differently look
        // like two unrelated statements: their changed sets do not intersect, the resolver calls that "disjoint
        // edits", and it combines both lines — producing a body with two conflicting statements and never asking the
        // comparison that can actually read the line. For a single-statement body that was the common case, not an
        // edge one.
        //
        // Preferring the ported pass is not a preference for a weaker answer: it composes only what it can justify
        // (one side changed, both changed identically, or the words compose) and REFUSES on a real disagreement —
        // two different insertions at one point, or two different replacements of the same token — which is exactly
        // when the statement-set logic below is the better (and only) answer. So the order is: the finer reading
        // first, the coarser one when the finer one refuses, and the level records which one answered.
        ConflictResolution composed = composedBeyondStatementSets(conflict);
        if (composed != null) {
            return composed;
        }

        if (!overlap.isEmpty()) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .analysisLevel(AnalysisLevel.TEXT_LOCAL)
                .explanation("Both branches changed the same statement(s) " + overlap
                    + ", so the edits cannot be combined mechanically.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        // Disjoint edits: combine, but still ask for confirmation.
        List<String> combined = new ArrayList<>();
        for (String statement : body2) {
            if (!combined.contains(statement)) {
                combined.add(statement);
            }
        }
        for (String statement : body1) {
            if (!combined.contains(statement)) {
                combined.add(statement);
            }
        }

        return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
            .resolvedCode(String.join("\n", combined))
            .analysisLevel(AnalysisLevel.TEXT_LOCAL)
            .explanation("Branch 1 changed " + changedByBranch1.size() + " statement(s) and "
                + "branch 2 changed " + changedByBranch2.size() + " different statement(s); "
                + "the combined body keeps both changes. Confirm the merged behaviour is "
                + "what was intended.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    /**
     * The intra-line path: ask the ported resolve pass, and record the level the answer actually needed
     * (plan steps 4.11 and 4.13; {@code JETBRAINS_PORT.md} § 11.2).
     *
     * <h2>Why the resolver asks rather than decides</h2>
     *
     * <p>The answer comes back as a <b>review</b> resolution, so nothing here is applied by the tool — the same
     * posture every other answer this resolver produces takes. What the pass adds is a composition the statement
     * sets cannot express: two sides that deleted <em>different words</em> of one line, which the benchmark's own
     * resolve vectors are about and which a line comparison can only call a disagreement.
     *
     * <h2>The level is the record of what was read, and that is asserted</h2>
     *
     * <p>{@code MergeResolve.Result.wordLevel()} says whether the word comparison was needed, and it decides which
     * level is recorded: {@link AnalysisLevel#TEXT_INTRALINE} when it was, {@link AnalysisLevel#TEXT_LOCAL} when the
     * line comparison alone reached the answer. A resolver that recorded the finer level for every answer would be
     * claiming a reading it did not do.
     *
     * @return the composed answer as a review resolution, or {@code null} when the pass refuses — which is the
     *         honest outcome for two different insertions at one point, and keeps this path from inventing one
     */
    private ConflictResolution composedBeyondStatementSets(Conflict conflict) {
        MergeResolve.Result composed = MergeResolve.resolve(conflict.getBranch1Code(),
            conflict.getBaseCode(), conflict.getBranch2Code(), ComparisonPolicy.DEFAULT);
        if (composed.refused()) {
            return null;
        }
        return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
            .resolvedCode(composed.mergedText())
            .analysisLevel(composed.wordLevel() ? AnalysisLevel.TEXT_INTRALINE : AnalysisLevel.TEXT_LOCAL)
            .explanation(composed.wordLevel()
                ? "Composed word by word inside the changed line: each branch kept words the other removed, and the"
                    + " words neither touched are the answer. Confirm the composed statement is what was intended."
                : "Composed from the changed lines: one branch changed what the other left alone.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        List<String> body1 = statementsIn(conflict.getBranch1Code());
        List<String> body2 = statementsIn(conflict.getBranch2Code());
        List<String> baseBody = statementsIn(conflict.getBaseCode());

        Set<String> changedByBranch1 = difference(body1, baseBody);
        Set<String> changedByBranch2 = difference(body2, baseBody);
        Set<String> overlap = new LinkedHashSet<>(changedByBranch1);
        overlap.retainAll(changedByBranch2);

        return List.of(
            newFixPath(conflict)
                .description(overlap.isEmpty()
                    ? "Combine the two independent edits"
                    : "Choose which edit to keep for the overlapping statements")
                .options("Combine both edits", "Keep branch 1's body", "Keep branch 2's body")
                .recommended(overlap.isEmpty() ? "Combine both edits" : null)
                .justification(overlap.isEmpty()
                    ? "The two edits touch disjoint statements, so combining them preserves "
                        + "both intents."
                    : "Both branches rewrote " + overlap + ", so keeping both is impossible.")
                .impact(overlap.isEmpty()
                    ? "Low, but the merged behaviour of two individually-correct edits is "
                        + "not guaranteed to be correct - review it."
                    : "One branch's intent is discarded or must be rewritten by hand.")
                .build(),
            newFixPath(conflict)
                .description("Take one side wholesale")
                .options("Branch 1's body", "Branch 2's body")
                .justification("Safest when the two edits are alternative implementations "
                    + "of the same fix.")
                .impact("Discards the other branch's change to this method.")
                .build()
        );
    }

    /**
     * The non-blank, non-comment statements of a code hunk, trimmed and in order.
     *
     * <p>Import and package declarations are excluded because they are handled by
     * their own conflict types. Counting them as statements would make a pure
     * import conflict look like a method-body edit as well, which would both
     * double-report it and hide the fact that it is trivially additive.
     */
    static List<String> statementsIn(String code) {
        List<String> statements = new ArrayList<>();
        if (code == null) {
            return statements;
        }
        for (String rawLine : code.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            if (isDeclarationHeader(line)) {
                continue;
            }
            statements.add(line);
        }
        return statements;
    }

    /**
     * True for lines that belong to the file's declaration section rather than to
     * a method body.
     */
    private static boolean isDeclarationHeader(String line) {
        return line.startsWith("import ") || line.startsWith("package ");
    }

    private static Set<String> difference(List<String> left, List<String> right) {
        Set<String> onlyInLeft = new LinkedHashSet<>(left);
        onlyInLeft.removeAll(new LinkedHashSet<>(right));
        return onlyInLeft;
    }
}
