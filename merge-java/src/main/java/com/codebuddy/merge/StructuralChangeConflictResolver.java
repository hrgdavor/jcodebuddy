// {@link com.codebuddy.merge.StructuralChangeConflictResolver} Handles structural changes requiring manual review.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Handles conflicts where whole members were added or removed in ways that do
 * not compose: a method deleted on one branch and modified on the other, or two
 * branches each reworking the same member differently.
 *
 * There is no safe automatic answer here, because any choice silently discards
 * someone's work. The resolver's job is therefore to refuse clearly and hand the
 * reviewer a precise description of what is at stake rather than a bare
 * {@code <<<<<<<} block.
 */
public final class StructuralChangeConflictResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.STRUCTURAL_CHANGE;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        // Never guesses. Returning null routes to the manual fallback, which is
        // enriched below with a structural diff summary.
        return null;
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        Set<String> onlyInBranch1 = linesOnlyIn(conflict.getBranch1Code(), conflict.getBranch2Code());
        Set<String> onlyInBranch2 = linesOnlyIn(conflict.getBranch2Code(), conflict.getBranch1Code());
        Set<String> onlyInBase = linesOnlyIn(conflict.getBaseCode(), conflict.getBranch1Code());

        return List.of(
            newFixPath(conflict)
                .description("Take branch 1's structure")
                .options("Apply branch 1", "Apply branch 2", "Combine both", "Rewrite by hand")
                .justification("Branch 1 removes or rewrites " + onlyInBase.size()
                    + " base line(s) and adds " + onlyInBranch1.size()
                    + " line(s) not present in branch 2.")
                .impact("Discards branch 2's structural work: "
                    + onlyInBranch2.size() + " line(s) unique to branch 2.")
                .build(),
            newFixPath(conflict)
                .description("Combine both structures")
                .options("Merge member by member", "Keep the union of members")
                .justification("Both branches may have added independent members that can "
                    + "coexist; only the overlapping members truly conflict.")
                .impact("Requires a reviewer to confirm no member is duplicated or lost.")
                .build(),
            manualFixPath(conflict)
        );
    }

    private static Set<String> linesOnlyIn(String code, String other) {
        Set<String> result = new TreeSet<>();
        if (code == null) {
            return result;
        }
        Set<String> otherLines = new TreeSet<>();
        if (other != null) {
            for (String line : other.split("\n")) {
                otherLines.add(line.trim());
            }
        }
        for (String line : code.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !otherLines.contains(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
