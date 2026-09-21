// {@link com.codebuddy.merge.TypeContext} Context needed to resolve types in conflicting code.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.java.JavaParser;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a resolver needs in order to resolve <em>types</em> rather than compare
 * text.
 *
 * <h2>Only some resolvers need this</h2>
 *
 * <p>Most conflicts are decidable without any type information, and requiring a
 * context for them would be an unnecessary burden on every caller. Placing imports
 * is the clearest example: two branches adding imports to the same neighbourhood
 * is resolved by de-duplicating and keeping both, which needs nothing but the text.
 * The same is true of comment and constant unions, and the structural and
 * API-conflict resolvers never decide anything at all.
 *
 * <p>A resolver that genuinely needs types declares it via
 * {@link ConflictResolver#requiresTypeContext()}. Today that is
 * {@link OverloadAddConflictResolver}, because deciding whether {@code List<String>}
 * and {@code java.util.List<java.lang.String>} are the same parameter list is a
 * question about resolved types, not about spelling.
 *
 * <p>When any registered resolver declares the requirement, the orchestrator
 * refuses to be built without a context rather than silently degrading to a weaker
 * comparison.
 *
 * @param sourceRoot where the conflicting sources are rooted, used to give the
 *                   parser a source path so it can attribute types
 * @param classpath  the compile classpath to resolve against; must not be empty,
 *                   because a parser with no classpath cannot resolve even
 *                   {@code java.util.List}
 */
public record TypeContext(Path sourceRoot, List<Path> classpath) {

    public TypeContext {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(classpath, "classpath");
        classpath = List.copyOf(classpath);
        if (classpath.isEmpty()) {
            throw new IllegalArgumentException(
                "a TypeContext needs a non-empty classpath; with nothing to resolve "
                    + "against, no type information can be attributed");
        }
    }

    /**
     * A context rooted at a directory with an explicit classpath.
     */
    public static TypeContext of(Path sourceRoot, List<Path> classpath) {
        return new TypeContext(sourceRoot, classpath);
    }

    /**
     * A context whose classpath is the current JVM's own classpath.
     *
     * <p>Convenient and usually sufficient for resolving JDK types — which is what
     * the conflict types that need types are about — but it will not resolve types
     * from the application under merge. Pass an explicit classpath when that
     * matters.
     */
    public static TypeContext withRuntimeClasspath(Path sourceRoot) {
        return new TypeContext(sourceRoot, JavaParser.runtimeClasspath());
    }

    /**
     * The source path to report for a conflicting file, so parse errors and type
     * attribution are anchored to a plausible location.
     *
     * <p>A path already within the source root is used as-is; otherwise it is
     * resolved against the root. A file with no path at all gets a stable
     * placeholder, because the parser needs something and an anonymous file is
     * still worth parsing.
     */
    public Path sourcePathFor(String filePath) {
        Path candidate = filePath == null || filePath.isBlank() || filePath.startsWith("<")
            ? Path.of("Conflicted.java")
            : Path.of(filePath);
        return candidate.isAbsolute() ? candidate : sourceRoot.resolve(candidate).normalize();
    }

    /**
     * A short description for diagnostics.
     */
    public String describe() {
        return "source root " + sourceRoot + ", " + classpath.size() + " classpath entries";
    }

    @Override
    public String toString() {
        return "TypeContext{" + describe() + '}';
    }
}
