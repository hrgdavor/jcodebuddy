// {@link com.codebuddy.merge.ResolvedTypeReaderTest} Pins the reader contract the resolvers rely on.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.JavaType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader's contract, pinned on its own now that it has two consumers.
 *
 * <p>{@code OverloadAddConflictResolver} asks it about method parameters and
 * {@code TypeChangeConflictResolver} about declared variables, and both depend on the
 * same distinction: <strong>"no answer" and "a negative answer" are different
 * things</strong>. A resolver may escalate on no answer; it must never read one as
 * "not assignable". That is the property these tests exist to hold, because it is
 * invisible at the call site — an empty {@code Optional} and a comparison that
 * returns false look the same to a careless caller.
 */
class ResolvedTypeReaderTest {

    /** A declaration of a JDK type resolves to that type. */
    @Test
    @DisplayName("resolves a declared variable to its type")
    void resolvesADeclaredVariable() {
        String code = "import java.util.*;\nclass A { Collection<String> value = null; }";

        Optional<JavaType> type =
            ResolvedTypeReader.declaredType(code, "value", "A.java", TestTypeContexts.jdk());

        assertTrue(type.isPresent(), "a JDK type must resolve against the JDK classpath");
        assertTrue(ResolvedTypeReader.renderType(type.get()).startsWith("java.util.Collection"),
            "and render as the resolved type: " + ResolvedTypeReader.renderType(type.get()));
    }

    /** A name the fragment does not declare is no answer, not a failure. */
    @Test
    @DisplayName("returns no answer for a name the fragment does not declare")
    void returnsNoAnswerForAnAbsentDeclaration() {
        String code = "class A { int present = 0; }";

        assertTrue(ResolvedTypeReader.declaredType(code, "absent", "A.java", TestTypeContexts.jdk())
            .isEmpty());
        assertTrue(ResolvedTypeReader.declaredType(code, "present", "A.java", TestTypeContexts.jdk())
            .isPresent(), "the declared one is still found");
    }

    /** No context is no answer, and never an exception. */
    @Test
    @DisplayName("returns no answer when there is no type context")
    void returnsNoAnswerWithoutAContext() {
        assertTrue(ResolvedTypeReader.declaredType("class A { int value = 0; }", "value",
            "A.java", null).isEmpty());
    }

    /** An empty or blank fragment is no answer rather than a parse failure. */
    @Test
    @DisplayName("treats a blank fragment as no answer")
    void blankFragmentIsNoAnswer() {
        assertTrue(ResolvedTypeReader.declaredType("   ", "value", "A.java", TestTypeContexts.jdk())
            .isEmpty());
        assertTrue(ResolvedTypeReader.declaredType(null, "value", "A.java", TestTypeContexts.jdk())
            .isEmpty());
    }

    /**
     * A type that is not on the classpath answers {@code Unknown}, <strong>not</strong>
     * an empty result — measured, and the distinction is load-bearing.
     *
     * <p>This is the case a project under merge hits by default: {@code MergeFileTool}
     * and {@code MergeWorkflow} build their context with
     * {@code TypeContext.withRuntimeClasspath(...)}, which carries the JDK and
     * merge-java's own classes but not the application's. So the presence check in
     * {@code TypeChangeConflictResolver.widensResolved} does <em>not</em> catch it, and
     * the comparison runs with an {@code Unknown} on one or both sides.
     *
     * <p>Two consequences are pinned here so neither is a surprise later:
     * <ul>
     *   <li>the safe outcome still holds — an unresolvable pair escalates, it is never
     *       auto-adopted, because {@code isAssignableTo} answers false for
     *       {@code Unknown};</li>
     *   <li>but "could not resolve" and "not a widening relationship" reach the reviewer
     *       as the <em>same</em> escalation, and the explanation currently says the
     *       second. Telling them apart needs a check on the resolved type itself
     *       ({@code JavaType.Unknown}), not on the {@code Optional} being empty.</li>
     * </ul>
     */
    @Test
    @DisplayName("reports an off-classpath project type as Unknown, and still escalates safely")
    void offClasspathTypeResolvesToUnknown() {
        String code = "class A { com.example.NotOnTheClasspath value = null; }";

        Optional<JavaType> type =
            ResolvedTypeReader.declaredType(code, "value", "A.java", TestTypeContexts.jdk());

        assertTrue(type.isPresent(), "an off-classpath type is present, not absent");
        assertTrue(type.get() instanceof JavaType.Unknown,
            "and it is JavaType.Unknown, which is why an emptiness check cannot detect it: "
                + type.map(String::valueOf).orElse("<empty>"));
        assertFalse(TypeChangeConflictResolver.widens(type.get(), type.get()),
            "Unknown is not assignable to anything, including itself");

        // The property that matters end to end: a pair this resolver cannot resolve goes
        // to a reviewer, never to an automatic adoption.
        Conflict conflict = new Conflict(ConflictType.TYPE_CHANGE, "A.java", "off classpath",
            "com.example.NotOnTheClasspath value = null;",
            "com.example.NotOnTheClasspath value = null;",
            "com.example.AlsoMissing value = null;")
            .withTypeContext(TestTypeContexts.jdk());
        ConflictResolution resolution = new TypeChangeConflictResolver().resolve(conflict);
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "an unresolvable pair escalates: " + resolution.getExplanation());
    }

    /**
     * The method channel keeps its two-part answer: a readable fragment reports no
     * failure, an unreadable one reports a message and no methods (F-34's "the verdict
     * is readable(), never 'a parse produced a result'").
     */
    @Test
    @DisplayName("reports a failure for an unreadable fragment and none for a readable one")
    void failureIsReportedForAnUnreadableFragment() {
        ResolvedTypeReader.Reading readable =
            ResolvedTypeReader.read("class A { void process(String id) { } }", "A.java",
                TestTypeContexts.jdk());
        assertTrue(readable.succeeded(), "a well-formed fragment parses: " + readable.failure());
        assertNotNull(readable.parametersOf("process"), "and its methods are indexed");

        ResolvedTypeReader.Reading broken =
            ResolvedTypeReader.read("class A { void process(String id", "A.java", TestTypeContexts.jdk());
        assertFalse(broken.succeeded(),
            "a truncated class has no verdict of its own and must be reported");
        assertNotNull(broken.failure(), "the failure carries the parser's own message");
        assertFalse(broken.failure().isBlank(), "and the message is not empty");
    }

    /** The parameter rendering is canonical, which is what makes two spellings compare. */
    @Test
    @DisplayName("renders equivalent parameter spellings identically")
    void rendersEquivalentSpellingsIdentically() {
        ResolvedTypeReader.Reading implicit =
            ResolvedTypeReader.read("import java.util.*;\nclass A { void m(List<String> id) { } }",
                "A.java", TestTypeContexts.jdk());
        ResolvedTypeReader.Reading explicit = ResolvedTypeReader.read(
            "class A { void m(java.util.List<java.lang.String> id) { } }", "A.java",
            TestTypeContexts.jdk());

        assertEquals(explicit.parametersOf("m"), implicit.parametersOf("m"),
            "List<String> and java.util.List<java.lang.String> are the same parameter list");
    }
}