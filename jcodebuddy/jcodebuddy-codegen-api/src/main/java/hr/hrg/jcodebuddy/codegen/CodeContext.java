package hr.hrg.jcodebuddy.codegen;

import java.nio.file.Path;

import hr.hrg.hipster.entity.tooling.meta.SourceMetadata;

/**
 * Where a generator is being asked to work, and what it is allowed to know about the place.
 *
 * <p>This is the one argument a {@link CodeGenerator} receives. It answers the questions a generator
 * actually has — which file, which line, how to indent, what types are in scope — and deliberately
 * answers no others: a generator that wants to read a database, a configuration file or another
 * project's sources has nothing here to do it with. That restraint is the same boundary JCodeBuddy
 * draws everywhere else, and it is why this type can live in a shared API rather than in the project
 * whose files are being written.
 *
 * <p>{@link #getTypeResolver()} is optional in practice — a generator that only rewrites syntax can
 * ignore it — and {@link #getSourceMetadata()} may be {@code null} for a file no metadata pass has
 * seen. Both are reported as they are rather than defaulted to something plausible, because a generator
 * that needs a type should say so by failing rather than by guessing.
 */
public interface CodeContext {

    /** The root of the source tree being generated into. */
    Path getRootPath();

    /** The file being generated, which may not exist yet. */
    Path getFilePath();

    /** The line the generator is anchored to, or 0 when it is not anchored. */
    int getLine();

    /** The indentation unit to use, so generated code matches its surroundings. */
    String getIndent();

    /** How to resolve a type name, or an empty resolver rather than {@code null}. */
    TypeResolver getTypeResolver();

    /** What a metadata pass knows about this file, or {@code null} when nothing does. */
    SourceMetadata getSourceMetadata();
}
