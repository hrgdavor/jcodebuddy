// {@link com.codebuddy.merge.ImportChange} What one branch did to the imports, relative to base.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.ParseExceptionResult;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a branch did to a file's imports, expressed as a change against the base
 * rather than as a snapshot of the branch.
 *
 * <h2>Why the base has to be in the comparison</h2>
 *
 * <p>Comparing the two branches to each other answers the wrong question. If ours
 * adds {@code java.time.Instant} and theirs removes {@code java.util.Set}, the two
 * import blocks differ, so a two-way comparison sees a clash over the import area
 * and reports a conflict. Against the base it is obvious that nothing actually
 * collides: an addition and a removal of <em>different</em> imports compose fine.
 *
 * <p>This is the failure the module exists to remove, so the change model has to be
 * three-way: {@code base → ours} and {@code base → theirs}, each producing
 * {@link #added()}, {@link #removed()} and {@link #retained()}. Both branches
 * removing the same import is agreement, not a conflict. One adding what the other
 * removes is a removal that must win, and is worth reporting as a decision rather
 * than silently resolved.
 *
 * <h2>Imports are read from the AST, not by matching text</h2>
 *
 * <p>Text scanning for lines beginning with {@code import} cannot see an import
 * split across lines, mis-reads {@code import} inside a comment, and has no notion
 * of static-ness. The AST gives all of that for free, and the parser is already a
 * declared dependency of this module.
 */
record ImportChange(Set<ImportRef> added, Set<ImportRef> removed, Set<ImportRef> retained) {

    /**
     * One import, identified by name and whether it is static.
     *
     * <p>Static-ness is part of the identity: {@code import static a.B.c} and
     * {@code import a.B.c} bind different things and must not be conflated.
     *
     * @param name       the fully qualified name, e.g. {@code java.util.List}
     * @param isStatic   whether the declaration had the {@code static} modifier
     * @param isWildcard whether the declaration was a wildcard import
     * @param rendered   how the declaration must be written back
     */
    record ImportRef(String name, boolean isStatic, boolean isWildcard, String rendered) {

        /**
         * Parse an import declaration's text into a reference.
         */
        static Optional<ImportRef> parse(String declaration) {
            if (declaration == null) {
                return Optional.empty();
            }
            String text = declaration.trim();
            if (!text.startsWith("import")) {
                return Optional.empty();
            }
            text = text.substring("import".length()).trim();
            boolean isStatic = false;
            if (text.startsWith("static")) {
                isStatic = true;
                text = text.substring("static".length()).trim();
            }
            if (text.endsWith(";")) {
                text = text.substring(0, text.length() - 1).trim();
            }
            if (text.isEmpty()) {
                return Optional.empty();
            }
            boolean isWildcard = text.endsWith(".*");
            String name = isWildcard ? text.substring(0, text.length() - 2) : text;
            String rendered = "import " + (isStatic ? "static " : "") + text + ";";
            return Optional.of(new ImportRef(name, isStatic, isWildcard, rendered));
        }

        /**
         * Ordering key used to keep merged output stable.
         */
        String sortKey() {
            return (isStatic ? "1" : "0") + name + (isWildcard ? ".*" : "");
        }
    }

    /**
     * True when neither branch touched the import block at all.
     */
    boolean isUnchanged() {
        return added.isEmpty() && removed.isEmpty();
    }

    /**
     * True when the two sides made the same removal decision.
     */
    boolean removesTheSameAs(ImportChange other) {
        return removed.equals(other.removed);
    }

    /**
     * True when a symbol was removed on one side and not on the other.
     *
     * <p>This - not an add-versus-remove pair - is the case the clash policy exists
     * for. A removal is the branch whose intent is ambiguous: it may have been
     * tidying an unused import, or it may have dropped one the other branch's code
     * still needs. Keeping and dropping are both defensible, and only the team knows
     * which branch was doing what.
     */
    boolean disagreesAboutRemoval(ImportChange other) {
        return !removesTheSameAs(other);
    }

    /**
     * The symbols whose removal is disputed: removed by one side, not by the other.
     *
     * <p>A symbol both sides removed is agreed and drops out. A symbol only one side
     * removed is exactly what the clash policy decides, whether or not the other side
     * mentions it at all - a branch that changed nothing still holds a position on a
     * removal, namely by keeping the import.
     */
    Set<ImportRef> removalsInDispute(ImportChange other) {
        Set<ImportRef> disputed = new LinkedHashSet<>(removed);
        disputed.addAll(other.removed);

        Set<ImportRef> agreed = new LinkedHashSet<>(removed);
        agreed.retainAll(other.removed);
        disputed.removeAll(agreed);

        return disputed;
    }

    /**
     * Compute what a branch changed, given the base.
     */
    static ImportChange between(Set<ImportRef> base, Set<ImportRef> branch) {
        Set<ImportRef> added = new LinkedHashSet<>(branch);
        added.removeAll(base);

        Set<ImportRef> removed = new LinkedHashSet<>(base);
        removed.removeAll(branch);

        Set<ImportRef> retained = new LinkedHashSet<>(branch);
        retained.retainAll(base);

        return new ImportChange(added, removed, retained);
    }

    /**
     * True when both branches made exactly the same change - agreement, not conflict.
     */
    boolean agreesWith(ImportChange other) {
        return added.equals(other.added) && removed.equals(other.removed);
    }

    /**
     * True when one side adds something the other removes. The removal must win:
     * re-adding a symbol the other branch deliberately dropped would resurrect code
     * that no longer compiles.
     */
    boolean clashesWith(ImportChange other) {
        for (ImportRef addedRef : added) {
            if (other.removed.contains(addedRef)) {
                return true;
            }
        }
        for (ImportRef removedRef : removed) {
            if (other.added.contains(removedRef)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The import block after applying both branches' changes, or empty when they
     * cannot be composed.
     *
     * <p>Removals are applied before additions, and a removal always wins over an
     * addition on the other side, because re-adding a deliberately removed import
     * is the one outcome that produces code no author intended.
     */
    Optional<List<ImportRef>> mergeWith(ImportChange other) {
        if (clashesWith(other)) {
            return Optional.empty();
        }
        Set<ImportRef> merged = new LinkedHashSet<>(retained);
        merged.addAll(other.retained);
        merged.removeAll(removed);
        merged.removeAll(other.removed);
        merged.addAll(added);
        merged.addAll(other.added);

        List<ImportRef> ordered = new ArrayList<>(merged);
        ordered.sort((left, right) -> left.sortKey().compareTo(right.sortKey()));
        return Optional.of(ordered);
    }

    /**
     * Read the imports declared in a source file.
     *
     * @return the imports, or empty when the file could not be parsed - which is
     *         deliberately different from "no imports", because an unreadable file
     *         must not be mistaken for an empty import block
     */
    static Optional<Set<ImportRef>> readImports(String code, String filePath, TypeContext context) {
        if (code == null || code.isBlank()) {
            return Optional.of(Set.of());
        }
        if (context == null) {
            // Fall back to the line scanner so import handling still works when no
            // type context is configured. Imports need no type resolution; the AST
            // is preferred only because it is more accurate.
            return Optional.of(ImportConflictResolver.extractImportsAsRefs(code));
        }

        Path sourcePath = context.sourcePathFor(filePath);
        JavaParser parser = JavaParser.fromJavaVersion()
            .classpath(context.classpath())
            .build();
        Path inputPath = parser.sourcePathFromSourceText(sourcePath, code);

        List<ImportRef> refs = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        try (Stream<SourceFile> parsed = parser.parseInputs(
                List.of(input(code, inputPath)),
                context.sourceRoot(),
                analysisContext())) {
            parsed.forEach(sourceFile -> {
                sourceFile.getMarkers().findAll(ParseExceptionResult.class).forEach(error ->
                    failures.add(error.getExceptionType() + ": " + error.getMessage()));
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Import visitImport(J.Import anImport, ExecutionContext ctx) {
                        ImportRef.parse(anImport.printTrimmed()).ifPresent(refs::add);
                        return super.visitImport(anImport, ctx);
                    }
                }.visit(sourceFile, new InMemoryExecutionContext());
            });
        } catch (RuntimeException e) {
            failures.add(e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }

        if (failures.isEmpty()) {
            return Optional.of(new LinkedHashSet<>(refs));
        }
        // A ParseError tree carries no imports, but the declaration lines are still
        // there in the text. Rather than report "no imports" - which would look like
        // a removal - fall back to the line scanner.
        return Optional.of(ImportConflictResolver.extractImportsAsRefs(code));
    }

    private static Parser.Input input(String code, Path sourcePath) {
        return new Parser.Input(sourcePath,
            () -> new java.io.ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8)));
    }

    private static ExecutionContext analysisContext() {
        InMemoryExecutionContext context = new InMemoryExecutionContext();
        // Fragments do not print back to themselves, which is expected here.
        context.putMessage("org.openrewrite.requirePrintEqualsInput", false);
        return context;
    }
}
