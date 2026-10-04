package hr.hrg.jcodebuddy.engine.codegen;

import hr.hrg.jcodebuddy.engine.DiagnosticSink;
import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.query.MetadataQuery;

import java.nio.file.Path;
import java.util.List;

/**
 * What a {@link ProjectGenerator} is handed: the project's <strong>model</strong>, and what to generate for.
 *
 * <p>This type is the reason {@link ProjectGenerator} can be a different interface rather than a bigger appetite.
 * A file-scoped generator is offered a {@link CodeContext} — one file, its path and its text — precisely because
 * the SPI promises that the file is all that matters. A generator that needs the project's <em>relations</em> cannot
 * be handed that, because "who extends whom" is not in any file; it is in the index. So the second kind gets a
 * context that carries the model and nothing else: no single file, no text to parse, no path to guess a sibling
 * from.</p>
 *
 * <p>{@code packages} is "what to generate for" for the generators that need telling — the entity metadata pass
 * takes a source root and a package list — and an empty list means the whole root. {@code diagnostics} is not
 * optional by accident: <strong>a type the metadata cannot resolve is reported, never inferred as
 * absent</strong> (the rule {@link hr.hrg.jcodebuddy.engine.index.TypeAnswer} exists to make hard, and the step 3.2
 * evidence is why it is a rule rather than a preference). A generator handed this context must send that report
 * here, and must not read an unanswered question as a negative answer.</p>
 *
 * @param sourceRoot  the root a generated file's package is resolved against, and the boundary of the model
 * @param index       the project's class index: one row per type, keyed by fully qualified name
 * @param query       the typed questions this generator may ask (relations, members, annotations, assignability)
 * @param diagnostics where a generator reports what it could not resolve
 * @param packages    the packages to generate for, or empty for the whole root
 */
public record ProjectContext(Path sourceRoot, ClassIndex index, MetadataQuery query,
                             DiagnosticSink diagnostics, List<String> packages) {

    public ProjectContext {
        packages = List.copyOf(packages);
    }

    /** The whole root, with no package restriction. */
    public static ProjectContext of(Path sourceRoot, ClassIndex index, MetadataQuery query,
                                    DiagnosticSink diagnostics) {
        return new ProjectContext(sourceRoot, index, query, diagnostics, List.of());
    }
}
