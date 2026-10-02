// {@link com.codebuddy.merge.TypeContext} Context needed to resolve types in conflicting code.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.java.JavaParser;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 * <p>A resolver that genuinely cannot work without compiled types declares it via
 * {@link ConflictResolver#requiresTypeContext()}, which makes the context a hard
 * requirement for the set it is in. Today that is {@link OverloadAddConflictResolver}:
 * deciding whether {@code List<String>} and {@code java.util.List<java.lang.String>}
 * are the same parameter list is a question about resolved types and nothing else, so
 * there is no weaker answer worth giving — the caller supplies a context or removes
 * that resolver from the set by hand.
 *
 * <p>{@link TypeChangeConflictResolver} is the other half of the rule: it
 * <strong>degrades</strong> instead. Part of its rule is the language's (the JLS 5.1.2
 * primitive conversions) and it carries a best-effort table of the common JDK
 * hierarchies, so without a context it still decides those cases — and attaches a
 * warning to every resolution it produces that way, because an answer reached without
 * compiled types must not read like one reached with them. With a context it resolves,
 * and a type it cannot resolve escalates with its own warning: it never falls back to
 * matching simple names while a classpath was available.
 *
 * <p>So "when any registered resolver declares the requirement, the orchestrator
 * refuses to be built without a context" holds for the hard requirement only, and
 * choosing between the two declarations is a decision about the resolver, not about
 * this record.
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
     * A context whose classpath is the JVM's own <strong>plus</strong> the given entries.
     *
     * <p>Additive rather than replacing, and that is measured rather than assumed: a
     * parser given only the extra entries resolves <em>neither</em> the project's types
     * <em>nor</em> {@code java.util} — the JVM classpath is what carries the platform —
     * so a caller who asked to resolve more would silently resolve less. The JVM's own
     * entries come first, so the platform and the tool's own dependencies win over a
     * duplicate in the caller's list.
     *
     * <p>This is the shape a command-line classpath flag wants: {@code --classpath}
     * means "here are the project's compiled classes", not "forget everything you knew".
     *
     * @param sourceRoot   where the conflicting sources are rooted
     * @param extraEntries the caller's entries — directories of compiled classes or jars
     */
    public static TypeContext withRuntimeClasspathAnd(Path sourceRoot, List<Path> extraEntries) {
        if (extraEntries == null || extraEntries.isEmpty()) {
            return withRuntimeClasspath(sourceRoot);
        }
        Set<Path> entries = new LinkedHashSet<>(JavaParser.runtimeClasspath());
        entries.addAll(extraEntries);
        return new TypeContext(sourceRoot, List.copyOf(entries));
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
