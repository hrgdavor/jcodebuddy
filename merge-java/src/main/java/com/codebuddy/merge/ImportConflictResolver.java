// {@link com.codebuddy.merge.ImportConflictResolver} Resolves import conflicts where both branches add needed imports.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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

    private final ImportClashPolicy clashPolicy;

    /**
     * A resolver using {@link ImportClashPolicy#defaultPolicy()}.
     */
    public ImportConflictResolver() {
        this(ImportClashPolicy.defaultPolicy());
    }

    /**
     * A resolver with an explicit policy for the add-versus-remove case.
     *
     * @param clashPolicy what to do when one branch adds an import the other removed
     */
    public ImportConflictResolver(ImportClashPolicy clashPolicy) {
        this.clashPolicy = clashPolicy == null ? ImportClashPolicy.defaultPolicy() : clashPolicy;
    }

    /**
     * The policy this resolver applies to the add-versus-remove case.
     */
    public ImportClashPolicy getClashPolicy() {
        return clashPolicy;
    }

    @Override
    public ConflictType supportedType() {
        return ConflictType.IMPORT_ADD;
    }

    @Override
    protected ConflictResolution doResolve(Conflict conflict) {
        // Read each side as a change against the base. Comparing the branches to
        // each other cannot tell an addition from a removal, so an import one side
        // added and the other dropped would read as a clash over the import block -
        // and re-adding a deliberately removed import is the one outcome that
        // produces code no author intended.
        Optional<ImportChange> ours = changeAgainstBase(conflict, conflict.getBranch1Code());
        Optional<ImportChange> theirs = changeAgainstBase(conflict, conflict.getBranch2Code());

        if (ours.isPresent() && theirs.isPresent()) {
            return resolveFromChanges(conflict, ours.get(), theirs.get());
        }

        // No base to compare against, or a side could not be read: fall back to the
        // union of whatever is present. Still correct for the additive case, which is
        // the only case a comparison without a base can establish.
        return resolveByUnion(conflict);
    }

    private Optional<ImportChange> changeAgainstBase(Conflict conflict, String branchCode) {
        Optional<Set<ImportChange.ImportRef>> base =
            ImportChange.readImports(conflict.getBaseCode(), conflict.getFilePath(),
                conflict.getTypeContext());
        Optional<Set<ImportChange.ImportRef>> branch =
            ImportChange.readImports(branchCode, conflict.getFilePath(), conflict.getTypeContext());
        if (base.isEmpty() || branch.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ImportChange.between(base.get(), branch.get()));
    }

    /**
     * Resolve from the two branches' changes against the base.
     */
    private ConflictResolution resolveFromChanges(Conflict conflict, ImportChange ours,
                                                  ImportChange theirs) {
        if (ours.isUnchanged() && theirs.isUnchanged()) {
            // Neither side touched the imports: not this resolver's conflict.
            return null;
        }

        // A removal on one side and not the other is the one ambiguous case, and it
        // is checked first: a branch that changed nothing still participates, because
        // "kept an import the other dropped" is a position on the removal.
        Set<ImportChange.ImportRef> disputed = ours.removalsInDispute(theirs);
        if (!disputed.isEmpty() && !ours.removesTheSameAs(theirs)) {
            boolean keep = clashPolicy.keepsTheImport();

            if (keep) {
                // Keep the symbol. An unused import is harmless, and this cannot break
                // a use of it on either branch, so it stays mechanical.
                return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                    .resolvedCode(render(renderKeepingAdditions(conflict)))
                    .explanation("The branches disagree about " + names(disputed) + ": ours "
                        + describe(ours) + ", theirs " + describe(theirs) + ". Applying the "
                        + clashPolicy + " policy - " + clashPolicy.rationale() + ".")
                    .alternativePaths(describeOptions(conflict))
                    .build();
            }

            // Honour the removal. This can break a use of the symbol on the other
            // branch, so it is escalated rather than applied.
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                .resolvedCode(render(renderKeepingRemovals(conflict)))
                .explanation("The branches disagree about " + names(disputed) + ": ours "
                    + describe(ours) + ", theirs " + describe(theirs) + ". Applying the "
                    + clashPolicy + " policy - " + clashPolicy.rationale() + " - so any use "
                    + "of a dropped symbol on the other branch will not compile until it is "
                    + "added back.")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        if (ours.agreesWith(theirs)) {
            // Both made the same change: agreement, so applying it once is correct.
            Optional<List<ImportChange.ImportRef>> merged = ours.mergeWith(theirs);
            return merged.map(refs -> autoResolution(conflict,
                        ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                    .resolvedCode(render(refs))
                    .explanation("Both branches made the same import change ("
                        + describe(ours) + "), so it is applied once.")
                    .alternativePaths(describeOptions(conflict))
                    .build())
                .orElse(null);
        }


        // Independent changes: compose them.
        Optional<List<ImportChange.ImportRef>> merged = ours.mergeWith(theirs);
        if (merged.isEmpty()) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .resolvedCode(conflict.getBranch1Code())
                .explanation("The import changes could not be composed: ours "
                    + describe(ours) + ", theirs " + describe(theirs) + ".")
                .alternativePaths(describeOptions(conflict))
                .build();
        }

        List<ImportChange.ImportRef> refs = merged.get();
        return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .resolvedCode(render(refs))
            .explanation("Composed the import changes: ours " + describe(ours)
                + ", theirs " + describe(theirs) + ".")
            .alternativePaths(describeOptions(conflict))
            .build();
    }

    private static List<ImportChange.ImportRef> mergeOrEmpty(ImportChange ours, ImportChange theirs) {
        return ours.mergeWith(theirs).orElseGet(List::of);
    }

    /**
     * The union with both branches' additions honoured.
     *
     * <p>Used when the {@link ImportClashPolicy} is {@link ImportClashPolicy#ADDITION_WINS}:
     * a symbol added on either side is kept, so no use of it can break.
     */
    private static List<ImportChange.ImportRef> renderKeepingAdditions(Conflict conflict) {
        Set<ImportChange.ImportRef> merged = new LinkedHashSet<>();
        for (String code : List.of(conflict.getBaseCode(), conflict.getBranch1Code(),
                conflict.getBranch2Code())) {
            merged.addAll(ImportChange.readImports(code, conflict.getFilePath(),
                conflict.getTypeContext()).orElseGet(Set::of));
        }

        List<ImportChange.ImportRef> ordered = new ArrayList<>(merged);
        ordered.sort(Comparator.comparing(ImportChange.ImportRef::sortKey));
        return ordered;
    }

    /**
     * The import block with both branches' removals honoured and their additions
     * kept where the other side did not remove them.
     */
    private static List<ImportChange.ImportRef> renderKeepingRemovals(Conflict conflict) {        Set<ImportChange.ImportRef> base =
            ImportChange.readImports(conflict.getBaseCode(), conflict.getFilePath(),
                conflict.getTypeContext()).orElseGet(Set::of);
        Set<ImportChange.ImportRef> ours =
            ImportChange.readImports(conflict.getBranch1Code(), conflict.getFilePath(),
                conflict.getTypeContext()).orElseGet(Set::of);
        Set<ImportChange.ImportRef> theirs =
            ImportChange.readImports(conflict.getBranch2Code(), conflict.getFilePath(),
                conflict.getTypeContext()).orElseGet(Set::of);

        Set<ImportChange.ImportRef> merged = new LinkedHashSet<>(base);
        merged.addAll(ours);
        merged.addAll(theirs);
        // Anything either side removed stays removed.
        base.stream().filter(ref -> !ours.contains(ref) || !theirs.contains(ref))
            .forEach(merged::remove);

        List<ImportChange.ImportRef> ordered = new ArrayList<>(merged);
        ordered.sort(Comparator.comparing(ImportChange.ImportRef::sortKey));
        return ordered;
    }

    private static String render(List<ImportChange.ImportRef> refs) {
        StringBuilder rendered = new StringBuilder();
        for (ImportChange.ImportRef ref : refs) {
            rendered.append(ref.rendered()).append('\n');
        }
        return rendered.toString();
    }

    /**
     * A short description of a change, for explanations a reviewer reads.
     */
    private static String describe(ImportChange change) {
        if (change.isUnchanged()) {
            return "changed nothing";
        }
        List<String> parts = new ArrayList<>();
        if (!change.added().isEmpty()) {
            parts.add("added " + names(change.added()));
        }
        if (!change.removed().isEmpty()) {
            parts.add("removed " + names(change.removed()));
        }
        return String.join(" and ", parts);
    }

    private static List<String> names(Set<ImportChange.ImportRef> refs) {
        List<String> names = new ArrayList<>();
        for (ImportChange.ImportRef ref : refs) {
            names.add(ref.name() + (ref.isWildcard() ? ".*" : ""));
        }
        Collections.sort(names);
        return names;
    }

    /**
     * The union of both sides, for when no base is available to compare against.
     */
    private ConflictResolution resolveByUnion(Conflict conflict) {
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
     * The imports in a hunk as structured references.
     *
     * <p>Used when no type context is available. Imports need no type resolution, so
     * the line scanner is sufficient here; it is only less accurate than the AST for
     * exotic formatting.
     */
    static Set<ImportChange.ImportRef> extractImportsAsRefs(String code) {
        Set<ImportChange.ImportRef> refs = new LinkedHashSet<>();
        for (String name : extractImports(code)) {
            ImportChange.ImportRef.parse("import " + name + ";").ifPresent(refs::add);
        }
        return refs;
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
