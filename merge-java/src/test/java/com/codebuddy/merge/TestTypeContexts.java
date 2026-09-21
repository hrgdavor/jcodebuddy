// {@link com.codebuddy.merge.TestTypeContexts} Shared type context for tests.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.openrewrite.java.JavaParser;

import java.nio.file.Path;
import java.util.List;

/**
 * Supplies a {@link TypeContext} for tests that need one.
 *
 * <p>Only the resolvers that make a judgement about types require a context, so
 * only the tests that exercise those need this. Placing imports, merging comments
 * and the rest work from the conflict text alone and are unaffected - which is why
 * the suite needed no wholesale rewrite when type context was introduced.
 *
 * <h2>Why the classpath is the test JVM's own</h2>
 *
 * <p>The parser resolves types against an explicit classpath, and the conflict
 * fixtures are about JDK types: {@code List}, {@code String}, {@code Map}. The test
 * JVM's classpath contains the JDK, so it resolves them. It is also a real,
 * non-empty classpath rather than a special case, so a test using it exercises the
 * same path a caller would.
 *
 * <p>Types from the project under merge would need the project's own classpath;
 * that is a property of the caller, not of the module, so it is not simulated here.
 */
final class TestTypeContexts {

    /**
     * A source root for fixtures. The files are never written: the parser is given
     * the code directly and uses the path only to attribute types.
     */
    static final Path SOURCE_ROOT = Path.of("target", "test-sources");

    private TestTypeContexts() {
    }

    /**
     * A context resolving JDK types, suitable for the conflict fixtures.
     */
    static TypeContext jdk() {
        return new TypeContext(SOURCE_ROOT, JavaParser.runtimeClasspath());
    }

    /**
     * A context with an empty classpath, for tests that assert the constructor
     * rejects one.
     */
    static List<Path> emptyClasspath() {
        return List.of();
    }
}
