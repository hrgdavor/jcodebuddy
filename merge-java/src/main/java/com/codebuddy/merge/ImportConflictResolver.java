// {@link com.codebuddy.merge.ImportConflictResolver} Resolves import conflicts where both branches add needed imports.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves conflicts where both branches add imports.
 *
 * This is the case ordinary merge tools handle worst: two branches each add an
 * import next to the same neighbourhood, and the tool reports a conflict even
 * though the correct answer is simply both imports. Adding a distinct import is
 * a commutative, additive change, so the union is always the right answer for
 * non-colliding imports.
 */
public final class ImportConflictResolver extends AbstractConflictResolver {

    @Override
    public ConflictType supportedType() {
        return ConflictType.IMPORT_ADD;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        Set<String> branch1Imports = extractImports(conflict.getBranch1Code());
        Set<String> branch2Imports = extractImports(conflict.getBranch2Code());

        Set<String> union = new LinkedHashSet<>(branch1Imports);
        union.addAll(branch2Imports);

        if (union.isEmpty()) {
            // Nothing import-shaped on either side: not ours to decide.
            return null;
        }

        Set<String> collisions = new LinkedHashSet<>(branch1Imports);
        collisions.retainAll(branch2Imports);

        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(renderImports(union))
            .explanation("Merged " + union.size() + " import(s) from both branches ("
                + collisions.size() + " shared). Additive and non-conflicting.")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    /**
     * Render the merged imports as valid Java statements. The resolved code is
     * meant to be written into a file, so it must be syntactically complete.
     */
    static String renderImports(Set<String> imports) {
        StringBuilder rendered = new StringBuilder();
        for (String value : imports) {
            rendered.append("import ").append(value).append(";\n");
        }
        return rendered.toString();
    }

    @Override
    protected List<FixPath> describeOptions(Conflict conflict) {
        Set<String> branch1Imports = extractImports(conflict.getBranch1Code());
        Set<String> branch2Imports = extractImports(conflict.getBranch2Code());

        List<String> options = new ArrayList<>();
        options.add("Keep both (" + unionSize(branch1Imports, branch2Imports) + " imports)");
        options.add("Keep branch 1 imports only (" + branch1Imports.size() + ")");
        options.add("Keep branch 2 imports only (" + branch2Imports.size() + ")");

        return List.of(
            newFixPath(conflict)
                .description("Keep the imports added by both branches")
                .options(options)
                .recommended(options.get(0))
                .justification("Import statements are additive; keeping both preserves "
                    + "every symbol either branch relies on.")
                .impact("None - the union compiles as long as no two imports bind the "
                    + "same simple name.")
                .build(),
            newFixPath(conflict)
                .description("Keep only one side's imports")
                .options("Keep branch 1", "Keep branch 2")
                .justification("Appropriate only when one branch's import is dead code.")
                .impact("Drops symbols the other branch may reference - expect "
                    + "compilation errors if it was not dead.")
                .build()
        );
    }

    private static int unionSize(Set<String> a, Set<String> b) {
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union.size();
    }

    /**
     * Extract the fully-qualified name of every import statement in a hunk.
     * Static-ness is preserved because {@code import static a.B.c} and
     * {@code import a.B.c} are different bindings.
     */
    static Set<String> extractImports(String code) {
        Set<String> imports = new LinkedHashSet<>();
        if (code == null) {
            return imports;
        }
        for (String rawLine : code.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("import ")) {
                String value = line.substring("import ".length()).trim();
                if (value.endsWith(";")) {
                    value = value.substring(0, value.length() - 1);
                }
                if (!value.isEmpty()) {
                    imports.add(value);
                }
            }
        }
        return imports;
    }
}
