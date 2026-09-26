package hr.hrg.jcodebuddy.codegen;

import java.nio.file.Path;

import hr.hrg.hipster.entity.tooling.meta.SourceMetadata;

/**
 * A {@link CodeContext} as a value, for tests and for a caller that has no framework around it.
 *
 * <p>The convenience constructors exist because the two optional parts — the type resolver and the
 * source metadata — are absent in the common case: a test building a context to hand a generator, or a
 * tool that knows the file and the line and nothing more. Requiring both every time made callers write
 * {@code TypeResolver.empty(), null} to say nothing, which is noise that hides the cases where a
 * generator really does receive them.
 *
 * @param root           the root of the source tree
 * @param file           the file being generated
 * @param line           the anchor line, or 0
 * @param indent         the indentation unit
 * @param typeResolver   how to resolve a type name; never null
 * @param sourceMetadata what a metadata pass knows, or null
 */
public record CodeContextImpl(Path root, Path file, int line, String indent, TypeResolver typeResolver,
                              SourceMetadata sourceMetadata) implements CodeContext {

    /** A context with an empty type resolver and no source metadata. */
    public CodeContextImpl(Path root, Path file, int line, String indent) {
        this(root, file, line, indent, TypeResolver.empty(), null);
    }

    /** A context with four spaces of indentation, an empty resolver and no metadata. */
    public CodeContextImpl(Path root, Path file, int line) {
        this(root, file, line, "    ");
    }

    @Override
    public Path getRootPath() {
        return root;
    }

    @Override
    public Path getFilePath() {
        return file;
    }

    @Override
    public int getLine() {
        return line;
    }

    @Override
    public String getIndent() {
        return indent;
    }

    @Override
    public TypeResolver getTypeResolver() {
        return typeResolver;
    }

    @Override
    public SourceMetadata getSourceMetadata() {
        return sourceMetadata;
    }
}
