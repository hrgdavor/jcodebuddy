// {@link com.codebuddy.merge.MethodBodyChangeConflictResolver} Resolves compatible method body changes.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

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

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        List<String> body1 = statementsIn(conflict.getBranch1Code());
        List<String> body2 = statementsIn(conflict.getBranch2Code());
        List<String> baseBody = statementsIn(conflict.getBaseCode());

        if (body1.isEmpty() || body2.isEmpty()) {
            return null;
        }

        Set<String> changedByBranch1 = difference(body1, baseBody);
        Set<String> changedByBranch2 = difference(body2, baseBody);

        Set<String> overlap = new LinkedHashSet<>(changedByBranch1);
        overlap.retainAll(changedByBranch2);

        // Same edit on both sides: nothing to reconcile.
        if (!overlap.isEmpty() && changedByBranch1.equals(changedByBranch2)) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("Both branches made the same " + overlap.size()
                    + " edit(s), so either side's body is already correct.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        if (!overlap.isEmpty()) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
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
            .explanation("Branch 1 changed " + changedByBranch1.size() + " statement(s) and "
                + "branch 2 changed " + changedByBranch2.size() + " different statement(s); "
                + "the combined body keeps both changes. Confirm the merged behaviour is "
                + "what was intended.")
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
