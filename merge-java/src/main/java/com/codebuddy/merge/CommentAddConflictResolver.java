// {@link com.codebuddy.merge.CommentAddConflictResolver} Resolves comment-only conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves conflicts where both branches added comments or documentation.
 *
 * Comments carry no semantics, so two branches adding different comments is
 * purely additive and the union is always safe. This is a common false conflict:
 * merge tools report it because the added lines are adjacent.
 */
public final class CommentAddConflictResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.COMMENT_ADD;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        List<String> branch1Comments = commentsIn(conflict.getBranch1Code());
        List<String> branch2Comments = commentsIn(conflict.getBranch2Code());

        if (branch1Comments.isEmpty() && branch2Comments.isEmpty()) {
            return null;
        }

        Set<String> union = new LinkedHashSet<>(branch1Comments);
        union.addAll(branch2Comments);

        if (union.isEmpty()) {
            return null;
        }

        int duplicates = branch1Comments.size() + branch2Comments.size() - union.size();

        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(String.join("\n", union))
            .explanation("Merged " + union.size() + " distinct comment(s) from both branches"
                + (duplicates > 0 ? ", dropping " + duplicates + " duplicate(s)" : "")
                + ". Comments carry no semantics, so the union is safe.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        List<String> branch1Comments = commentsIn(conflict.getBranch1Code());
        List<String> branch2Comments = commentsIn(conflict.getBranch2Code());
        Set<String> union = new LinkedHashSet<>(branch1Comments);
        union.addAll(branch2Comments);

        return List.of(
            newFixPath(conflict)
                .description("Keep the comments from both branches")
                .options("Keep both (" + union.size() + " comment(s))",
                    "Keep branch 1's comments", "Keep branch 2's comments")
                .recommended("Keep both (" + union.size() + " comment(s))")
                .justification("Documentation is additive; each branch explained a different "
                    + "part of the change.")
                .impact("None - comments do not affect behaviour.")
                .build(),
            newFixPath(conflict)
                .description("Keep only one side's comments")
                .options("Keep branch 1", "Keep branch 2")
                .justification("Appropriate when one side's comment is now stale or wrong.")
                .impact("Loses documentation that may still be accurate.")
                .build()
        );
    }

    /**
     * The comment text on each line of a hunk, with the marker stripped.
     */
    static List<String> commentsIn(String code) {
        List<String> comments = new ArrayList<>();
        if (code == null) {
            return comments;
        }
        for (String rawLine : code.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("//")) {
                String text = line.substring(2).trim();
                if (!text.isEmpty()) {
                    comments.add(text);
                }
            } else if (line.startsWith("/*") && line.endsWith("*/") && line.length() > 4) {
                String text = line.substring(2, line.length() - 2).trim();
                if (!text.isEmpty()) {
                    comments.add(text);
                }
            } else if (line.startsWith("*") && !line.startsWith("*/")) {
                String text = line.substring(1).trim();
                if (!text.isEmpty()) {
                    comments.add(text);
                }
            }
        }
        return comments;
    }
}
