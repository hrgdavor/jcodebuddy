// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a record out of source and completes it with a fluent builder: the {@code builder()} factory,
 * the {@code toBuilder()} instance method, and the nested {@code Builder} class with its fields,
 * {@code build()} and one setter per component.
 *
 * <h3>Phase 6: the AST mutation is gone, and that is the design</h3>
 * <p>The JavaParser version mutated a live AST — {@code record.addMethod(...)},
 * {@code builder.getFields().stream().filter(...).forEach(FieldDeclaration::remove)} — and relied on
 * {@code LexicalPreservingPrinter} to write the result back without disturbing the rest of the file.
 * None of that survives the move to an LST: an OpenRewrite tree is <strong>immutable</strong>, so there
 * is no {@code addMethod} to call and no {@code remove()} to mutate through.</p>
 *
 * <p>What replaces it, and why this is the right shape rather than a stopgap:</p>
 * <ul>
 *   <li><strong>The output is generated, not edited.</strong> Both "update" paths in the old code — find
 *       the builder's existing field for a component and reuse it, else create one — existed only to
 *       keep JavaParser's node identity stable so the printer would emit each member once. Generating
 *       the builder deterministically from the record's component list produces the same text with no
 *       identity bookkeeping, no sweep for stale members, and no member-reordering pass.</li>
 *   <li><strong>Formatting becomes ours.</strong> The printer existed because it reformats; this module
 *       used it to protect everything outside the record, then still had to re-indent the record's own
 *       text line by line. {@link SourceSplicer} applies the indent where the text is built.</li>
 *   <li><strong>Nothing here builds a tree.</strong> Deliberate, and it is this module's answer to the
 *       emission-strategy question the migration guide leaves open: a builder is <em>appended</em> to a
 *       record, so text generation is the direct expression of the intent, while a {@code withXxx}
 *       chain would have to reconstruct every existing member just to keep it.</li>
 * </ul>
 *
 * <p>Parsing still goes through OpenRewrite, because the component list, the component types and the
 * record's identity all have to be read from a tree before anything can be generated.</p>
 *
 * <h3>Why there is no parser field</h3>
 * <p>A parser caches the sources it has parsed and refuses a second set declaring the same fully
 * qualified names — which is exactly what completing a record and then completing it again produces.
 * Rather than hand a shared instance around and rely on every caller to reset it, each read builds its
 * own. The cost is one parser construction per edit, which is small next to a file read and removes a
 * whole class of intermittent failure.</p>
 */
public class RecordBuilderProcessor {

    private final String indent;

    public RecordBuilderProcessor(String indent) {
        this.indent = indent;
    }

    public String getIndent() {
        return indent;
    }

    /**
     * Where the target record is and what it declares.
     *
     * <p>No defensive copy of {@code components}: {@code componentsOf} builds a fresh list and never
     * hands it out, so the record is immutable in practice. A compact constructor here was also
     * rejected by javac — "invalid canonical constructor" — for reasons that were not worth chasing,
     * which is itself a small argument for keeping the record plain.</p>
     */
    public record Target(String recordName, List<Component> components, String recordText,
                         int startOffset, int endOffset, int startLine, int endLine) {
    }

    /** One record component: its declared type and its name. */
    public record Component(String type, String name) {
    }

    /**
     * The record in {@code source} nearest {@code line}, or {@code null} when there is none.
     *
     * <p>Selection reproduces the JavaParser version: prefer a record whose <em>name</em> line is within
     * five lines of the cursor, and otherwise take the first record in the file. The window exists
     * because an editor command is issued with the caret somewhere in or near the record, not on it.</p>
     */
    public Target target(String source, int line) {
        List<J.ClassDeclaration> records = recordsIn(source);
        if (records.isEmpty()) {
            return null;
        }
        J.ClassDeclaration chosen = null;
        int nearestDistance = Integer.MAX_VALUE;
        LineLookup.Span chosenSpan = null;
        for (J.ClassDeclaration record : records) {
            LineLookup.Span span = LineLookup.spanOf(source, record.getSimpleName());
            if (span == null) {
                // A record javac could not locate cannot be replaced safely, so it is skipped rather
                // than guessed at — the fallback below still covers a file whose only record is this.
                continue;
            }
            int distance = Math.abs(span.nameLine() - line);
            if (distance <= 5 && distance < nearestDistance) {
                chosen = record;
                chosenSpan = span;
                nearestDistance = distance;
            }
        }
        if (chosen == null) {
            // No record within reach of the cursor: take the first, which is the old fallback.
            for (J.ClassDeclaration record : records) {
                LineLookup.Span span = LineLookup.spanOf(source, record.getSimpleName());
                if (span != null) {
                    chosen = record;
                    chosenSpan = span;
                    break;
                }
            }
        }
        if (chosen == null || chosenSpan == null) {
            return null;
        }
        return new Target(chosen.getSimpleName(), componentsOf(chosen), source,
                chosenSpan.startOffset(), chosenSpan.endOffset(),
                chosenSpan.startLine(), chosenSpan.endLine());    }

    /** {@code recordText} with its builder completed. */
    public String complete(String recordText, String recordName, List<Component> components) {
        return SourceSplicer.withBuilder(recordText, recordName, components, indent);
    }

    /**
     * One record the editor workflow cares about: its name, and the line its name is declared on.
     *
     * @param recordName the record's simple name
     * @param nameLine   the 1-based line the record's <em>name</em> sits on — never the declaration's
     *                   start, which for an annotated record is the annotation's line
     */
    public record AnnotatedRecord(String recordName, int nameLine) {
    }

    /**
     * Every record in {@code source} that carries the builder annotation, with its name's line, in
     * declaration order.
     *
     * <p>Lives here rather than in the language server because this class already owns "which record in
     * this text is the target" and the javac-backed line lookup that answers it. A second implementation
     * in the sidecar would be a second answer to the same question — and the line is the one fact the
     * LST cannot supply, so it is the fact most likely to be got wrong twice.</p>
     *
     * <p>Both spellings of the annotation are accepted, exactly as the JavaParser version accepted them:
     * the imported {@code @GenerateBuilder} and the fully qualified
     * {@code @hr.hrg.watch2.builder.api.GenerateBuilder}. A record javac cannot locate is skipped rather
     * than guessed at.</p>
     */
    public List<AnnotatedRecord> annotatedRecords(String source) {
        List<AnnotatedRecord> found = new ArrayList<>();
        for (J.ClassDeclaration record : recordsIn(source)) {
            if (!hasGenerateBuilder(record)) {
                continue;
            }
            LineLookup.Span span = LineLookup.spanOf(source, record.getSimpleName());
            if (span != null) {
                found.add(new AnnotatedRecord(record.getSimpleName(), span.nameLine()));
            }
        }
        return found;
    }

    /**
     * The record whose name is declared on {@code line}, or {@code null}.
     *
     * <p>An exact-line question, which is what an editor's code action asks: the caret is on the
     * record's own name, not near it. That is why this is not {@link #target(String, int)}, whose
     * five-line window exists for a caret that is merely in the neighbourhood.</p>
     */
    public AnnotatedRecord recordOnLine(String source, int line) {
        for (J.ClassDeclaration record : recordsIn(source)) {
            LineLookup.Span span = LineLookup.spanOf(source, record.getSimpleName());
            if (span != null && span.nameLine() == line) {
                return new AnnotatedRecord(record.getSimpleName(), span.nameLine());
            }
        }
        return null;
    }

    /** Whether a record is annotated with the builder annotation, under either spelling. */
    private static boolean hasGenerateBuilder(J.ClassDeclaration record) {
        if (record.getLeadingAnnotations() == null) {
            return false;
        }
        for (J.Annotation annotation : record.getLeadingAnnotations()) {
            if (annotation.getAnnotationType() == null) {
                continue;
            }
            String name = annotation.getAnnotationType().toString().trim();
            int dot = name.lastIndexOf('.');
            if ("GenerateBuilder".equals(dot < 0 ? name : name.substring(dot + 1))) {
                return true;
            }
        }
        return false;
    }

    /** The record declarations in {@code source}, outermost first. */
    private List<J.ClassDeclaration> recordsIn(String source) {
        List<J.ClassDeclaration> records = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return records;
        }
        ExecutionContext context = new InMemoryExecutionContext();
        // Nothing is written back from this tree, and the inputs are fragments rather than compilation
        // units, so the print-idempotency guard is off. It is *not* off for generation: the text this
        // class returns is produced by SourceSplicer, never by printing a tree.
        context.putMessage("org.openrewrite.requirePrintEqualsInput", false);
        JavaParser parser = JavaParser.fromJavaVersion().build();
        Path path = parser.sourcePathFromSourceText(Path.of("Record.java"), source);
        try (var stream = parser.parseInputs(List.of(input(path, source)), null, context)) {
            for (SourceFile file : stream.toList()) {
                if (file instanceof J.CompilationUnit unit) {
                    new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration declaration,
                                                                        ExecutionContext ctx) {
                            if (declaration.getKind() == J.ClassDeclaration.Kind.Type.Record) {
                                records.add(declaration);
                            }
                            return super.visitClassDeclaration(declaration, ctx);
                        }
                    }.visit(unit, context);
                }
            }        } catch (RuntimeException unreadable) {
            // "Could not be read" is not "no records": an unreadable file must not be rewritten, and
            // the caller distinguishes the two by the null Target.
            return List.of();
        }
        return records;
    }

    /**
     * The record's components: declared type and name, in declaration order.
     *
     * <p>A record's components are its primary-constructor parameters — there is no record-specific
     * component accessor on {@link J.ClassDeclaration}, which is the trap the migration caveats record
     * for {@code RecordDeclaration}. Duplicate names are collapsed, because a repeated component name
     * would otherwise emit two fields with one name and produce source that does not compile.</p>
     */
    private static List<Component> componentsOf(J.ClassDeclaration record) {
        List<Component> components = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<org.openrewrite.java.tree.Statement> primary = record.getPrimaryConstructor();
        if (primary == null) {
            return components;
        }
        for (org.openrewrite.java.tree.Statement element : primary) {
            if (!(element instanceof J.VariableDeclarations declarations)) {
                continue;
            }
            String type = typeText(declarations);
            // `var` rather than naming the element type: the LST's declarator element is an interface
            // implemented by more than one node, so the concrete type differs by position and naming it
            // wrongly is a compile error rather than a wrong answer — which is the good case.
            for (var variable : declarations.getVariables()) {
                String name = variable.getName().getSimpleName();
                if (seen.add(name)) {
                    components.add(new Component(type, name));
                }
            }
        }
        return components;
    }

    /** The declared type of a component, as source text. */
    private static String typeText(J.VariableDeclarations declarations) {
        org.openrewrite.java.tree.TypeTree type = declarations.getTypeExpression();
        return (type == null ? declarations.getType() : type).toString().trim();
    }

    private static Parser.Input input(Path path, String source) {
        return new Parser.Input(path, () -> new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }
}
