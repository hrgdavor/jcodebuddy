// {@link com.codebuddy.merge.RenameConflictResolver} Resolves variable rename conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves conflicts where the same identifier was renamed differently on each
 * branch.
 *
 * Unlike an import addition, a rename is a choice, not a fact: both
 * {@code order} and {@code purchase} may be valid, but the codebase must end up
 * using one consistently. This resolver therefore never applies a rename
 * silently. It identifies the competing names, offers them as fix paths, and
 * records the reviewer's pick as a <em>sticky decision</em> so the same rename
 * is applied automatically on the next base-branch update.
 */
public final class RenameConflictResolver extends AbstractConflictResolver {

    /**
     * Matches a simple local/field declaration: {@code Type name = ...},
     * {@code var name = ...}, {@code final Type name;}.
     * Deliberately conservative - a bare identifier is not treated as a rename.
     */
    private static final Pattern DECLARATION = Pattern.compile(
        "^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*"
            + "[A-Za-z_$][\\w$]*(?:\\s*<[^>]*>)?(?:\\s*\\[\\s*\\])*\\s+"
            + "([A-Za-z_$][\\w$]*)\\s*(?:=|;)");

    @Override
    public ConflictType supportedType() {
        return ConflictType.VARIABLE_RENAME;
    }

    /**
     * A rename is a user intent, so the answer must be reusable next time.
     */
    @Override
    protected boolean stickyByDefault() {
        return true;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        Optional<String> baseName = firstDeclaredName(conflict.getBaseCode());
        Optional<String> branch1Name = firstDeclaredName(conflict.getBranch1Code());
        Optional<String> branch2Name = firstDeclaredName(conflict.getBranch2Code());

        if (branch1Name.isEmpty() || branch2Name.isEmpty()) {
            return null;
        }

        // No rename at all on one side: nothing to arbitrate.
        if (branch1Name.equals(branch2Name)) {
            return null;
        }

        // Both branches agree on a name that differs from base: safe to adopt.
        boolean agreesWithBase = baseName.isPresent()
            && (baseName.get().equals(branch1Name.get()) || baseName.get().equals(branch2Name.get()));

        return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
            .resolvedCode(conflict.getBranch1Code())
            .explanation("Both branches renamed the same declaration: '"
                + branch1Name.get() + "' (branch 1) vs '" + branch2Name.get() + "' (branch 2)"
                + (agreesWithBase ? "; one side kept the base name" : "")
                + ". One name must win for the codebase to stay consistent.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        String baseName = firstDeclaredName(conflict.getBaseCode()).orElse("<base>");
        String branch1Name = firstDeclaredName(conflict.getBranch1Code()).orElse("<branch 1>");
        String branch2Name = firstDeclaredName(conflict.getBranch2Code()).orElse("<branch 2>");

        String keepBranch1 = "Rename to '" + branch1Name + "' everywhere";
        String keepBranch2 = "Rename to '" + branch2Name + "' everywhere";
        String keepBase = "Keep the base name '" + baseName + "'";

        FixPath.Builder primary = newFixPath(conflict)
            .description("Adopt one name for both branches")
            .options(keepBranch1, keepBranch2, keepBase)
            .justification("A rename conflict is resolved by picking a single spelling; "
                + "the choice is recorded and replayed on later updates.")
            .impact("Renames every reference at the merge site; callers outside the "
                + "hunk must already use the chosen name or be updated.");

        // Recommend whichever side matches base when there is one, otherwise the
        // first branch: this minimises the diff against base.
        if (branch1Name.equals(baseName)) {
            primary.recommended(keepBranch1);
        } else if (branch2Name.equals(baseName)) {
            primary.recommended(keepBranch2);
        } else {
            primary.recommended(keepBranch1);
        }

        return List.of(
            primary.build(),
            newFixPath(conflict)
                .description("Make the rename decision sticky")
                .options("Remember my choice for this declaration", "Ask me again next time")
                .justification("Recording the decision removes the same conflict from "
                    + "every future base-branch update on this branch.")
                .impact("Later merges apply the recorded rename without prompting.")
                .build()
        );
    }

    /**
     * The first simple name declared inside a code hunk, if any.
     */
    static Optional<String> firstDeclaredName(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (String line : code.split("\n")) {
            Matcher matcher = DECLARATION.matcher(line);
            if (matcher.find()) {
                String name = matcher.group(1);
                if (isPlausibleIdentifier(name)) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isPlausibleIdentifier(String name) {
        Objects.requireNonNull(name, "name");
        // Exclude control-flow and modifier keywords that the regex can swallow.
        return switch (name) {
            case "if", "for", "while", "switch", "return", "new", "class", "catch", "try" -> false;
            default -> !name.isEmpty();
        };
    }
}
