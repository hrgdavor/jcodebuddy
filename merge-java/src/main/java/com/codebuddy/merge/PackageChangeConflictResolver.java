// {@link com.codebuddy.merge.PackageChangeConflictResolver} Resolves package move conflicts.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves conflicts where a class was moved to a different package on each
 * branch.
 *
 * A package is a naming decision, and a class cannot live in two packages at
 * once, so this resolver does not guess. It extracts the competing package
 * declarations, surfaces both as fix paths, and is sticky: the chosen home is
 * recorded so the same move is applied automatically on the next update.
 */
public final class PackageChangeConflictResolver extends AbstractConflictResolver {

    private static final Pattern PACKAGE_DECLARATION =
        Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");

    @Override
    public ConflictType supportedType() {
        return ConflictType.PACKAGE_CHANGE;
    }

    @Override
    protected boolean stickyByDefault() {
        return true;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        Optional<String> package1 = firstPackage(conflict.getBranch1Code());
        Optional<String> package2 = firstPackage(conflict.getBranch2Code());

        if (package1.isEmpty() || package2.isEmpty() || package1.equals(package2)) {
            return null;
        }

        Optional<String> basePackage = firstPackage(conflict.getBaseCode());
        boolean branch1KeepsBase = basePackage.isPresent() && basePackage.equals(package1);
        boolean branch2KeepsBase = basePackage.isPresent() && basePackage.equals(package2);

        // One branch moved the class and the other left it alone: the move is
        // the only deliberate change, so adopt it.
        if (branch1KeepsBase != branch2KeepsBase) {
            boolean branch1Moved = !branch1KeepsBase;
            return autoResolution(conflict, branch1Moved
                    ? ConflictResolution.ResolutionStrategy.PREFER_BRANCH1
                    : ConflictResolution.ResolutionStrategy.PREFER_BRANCH2)
                .resolvedCode(branch1Moved ? conflict.getBranch1Code() : conflict.getBranch2Code())
                .explanation("Only one branch moved the class (to '"
                    + (branch1Moved ? package1.get() : package2.get())
                    + "'); the other left it in '" + basePackage.orElse("<base>")
                    + "', so the move is adopted.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        // Both branches moved it somewhere different: a genuine disagreement.
        return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
            .resolvedCode(conflict.getBranch1Code())
            .explanation("Both branches moved the class, to '" + package1.get()
                + "' and '" + package2.get() + "'. One destination must win.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        String package1 = firstPackage(conflict.getBranch1Code()).orElse("<branch 1>");
        String package2 = firstPackage(conflict.getBranch2Code()).orElse("<branch 2>");
        String basePackage = firstPackage(conflict.getBaseCode()).orElse("<base>");

        List<String> options = new ArrayList<>();
        options.add("Move to '" + package1 + "'");
        options.add("Move to '" + package2 + "'");
        options.add("Keep '" + basePackage + "'");

        FixPath.Builder primary = newFixPath(conflict)
            .description("Choose the class's home package")
            .options(options)
            .justification("A class has exactly one package; the choice also fixes every "
                + "import of this class across the repository.")
            .impact("Moving a package changes imports at every call site, so the change "
                + "must be applied repository-wide, not just in this file.");

        // Prefer the destination that is not the base package when base is one of
        // the two, otherwise prefer branch 1 for determinism.
        if (package2.equals(basePackage)) {
            primary.recommended(options.get(0));
        } else if (package1.equals(basePackage)) {
            primary.recommended(options.get(1));
        } else {
            primary.recommended(options.get(0));
        }

        List<String> movedImports = importsFor(conflict.getBranch1Code());
        List<String> otherImports = importsFor(conflict.getBranch2Code());

        return List.of(
            primary.build(),
            newFixPath(conflict)
                .description("Re-point imports of the moved class")
                .options(movedImports.isEmpty() && otherImports.isEmpty()
                    ? "No import updates detected in this hunk"
                    : "Update imports to the chosen package")
                .justification(movedImports.isEmpty() && otherImports.isEmpty()
                    ? "Neither side touched an import in this hunk."
                    : "Branch 1 imports " + movedImports + " while branch 2 imports "
                        + otherImports + "; they must agree with the chosen package.")
                .impact("Un-updated imports fail to compile.")
                .build()
        );
    }

    /**
     * The package declared in a code hunk, if any.
     */
    static Optional<String> firstPackage(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (String line : code.split("\n")) {
            Matcher matcher = PACKAGE_DECLARATION.matcher(line);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private static List<String> importsFor(String code) {
        return ImportConflictResolver.extractImports(code).stream()
            .sorted()
            .collect(Collectors.toList());
    }
}
