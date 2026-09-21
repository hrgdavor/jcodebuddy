// {@link com.codebuddy.merge.OverloadAddConflictResolverTest} Tests for the overload conflict resolver.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that two branches adding distinct overloads are merged automatically,
 * while an identical parameter list is escalated.
 */
class OverloadAddConflictResolverTest extends AbstractResolverTest {

    private final OverloadAddConflictResolver resolver = new OverloadAddConflictResolver();

    @Override
    protected ConflictResolver resolverUnderTest() {
        return resolver;
    }

    @Override
    protected Conflict conflictFor(ConflictType type) {
        return ConflictFixtures.sample(type);
    }

    @Override
    protected List<ConflictType> unsupportedTypes() {
        return List.of(ConflictType.IMPORT_ADD, ConflictType.COMMENT_ADD);
    }

    /**
     * Resolve with the type context this resolver requires.
     *
     * <p>Parameter comparison is a question about resolved types, so the resolver
     * declares {@link ConflictResolver#requiresTypeContext()} and reports a
     * conflict it cannot decide when none is present. Attaching the context here
     * keeps each test about the decision rather than about parser wiring.
     */
    private ConflictResolution resolveWithTypes(Conflict conflict) {
        return resolver.resolve(conflict.withTypeContext(TestTypeContexts.jdk()));
    }

    @Test
    @DisplayName("keeps both methods when the parameter lists differ")
    void keepsDistinctOverloads() {
        ConflictResolution resolution = resolveWithTypes(
            ConflictFixtures.sample(ConflictType.OVERLOAD_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "distinct parameter lists are overloads, not a conflict: "
                + resolution.getExplanation());
        assertEquals(ConflictResolution.ResolutionStrategy.KEEP_BOTH,
            resolution.getResolutionStrategy());
        assertTrue(resolution.getResolvedCode().contains("process(String id)"),
            "branch 1's overload must survive");
        assertTrue(resolution.getResolvedCode().contains("process(String id, boolean force)"),
            "branch 2's overload must survive");
    }

    @Test
    @DisplayName("escalates when both branches add the same parameter list")
    void escalatesOnIdenticalSignatures() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "same parameter list",
            "void process() { }",
            "void process() { }\nvoid process(String id) { }",
            "void process() { }\nvoid process(String id) { }");

        ConflictResolution resolution = resolveWithTypes(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "identical signatures cannot both exist: " + resolution.getExplanation());
        assertTrue(resolution.getExplanation().contains("same"),
            "the explanation must say why: " + resolution.getExplanation());
    }

    @Test
    @DisplayName("escalates to manual when no type context is supplied")
    void escalatesWithoutTypeContext() {
        // The comparison cannot be made, so the conflict must not be guessed at.
        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.OVERLOAD_ADD));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "without resolved types the comparison cannot be trusted: "
                + resolution.getExplanation());
        assertEquals(ConflictResolution.MANUAL_MARKER, resolution.getResolvedCode(),
            "nothing may be applied");
        assertTrue(resolution.getAlternativePaths().stream()
                .anyMatch(path -> path.getJustification().contains("type context")),
            "the reviewer must be told how to fix it: " + resolution.getAlternativePaths());
    }

    @Test
    @DisplayName("resolves the same parameter written differently")
    void resolvesEquivalentParameterSpellings() {
        // The defect that motivated type-aware comparison: these two branches add
        // the same overload, spelled differently, so it is a collision - and the
        // text comparison called them distinct.
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "same parameter, different spelling",
            "void process() { }",
            "import java.util.List;\nvoid process() { }\nvoid process(List<String> id) { }",
            "void process() { }\n"
                + "void process(java.util.List<java.lang.String> id) { }");

        ConflictResolution resolution = resolveWithTypes(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "resolved types make these one signature, so keeping both would not compile: "
                + resolution.getExplanation());
    }

    @Test
    @DisplayName("keeps overloads whose resolved parameter types genuinely differ")
    void keepsOverloadsWithDifferentResolvedTypes() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "different generic arguments",
            "void process() { }",
            "import java.util.List;\nvoid process() { }\nvoid process(List<String> id) { }",
            "import java.util.List;\nvoid process() { }\nvoid process(List<Integer> id) { }");

        ConflictResolution resolution = resolveWithTypes(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "List<String> and List<Integer> are different parameter types: "
                + resolution.getExplanation());
    }

    @Test
    @DisplayName("declines when it is not the same method name")
    void declinesForDifferentMethodNames() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "different methods",
            "void process() { }",
            "void process() { }\nvoid charge(String id) { }",
            "void process() { }\nvoid refund(String id) { }");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind(),
            "two different method names are not an overload conflict");
    }

    @Test
    @DisplayName("declines when no method declaration is present")
    void declinesWithoutMethodDeclaration() {
        Conflict conflict = new Conflict(ConflictType.OVERLOAD_ADD, ConflictFixtures.FILE,
            "no methods", "int total = 0;", "int total = 1;", "int total = 2;");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(conflict).getKind());
    }

    @Test
    @DisplayName("reads resolved parameter types from a declaration")
    void readsResolvedParameterTypes() {
        ResolvedTypeReader.Reading reading = ResolvedTypeReader.read(
            "void process(String id, boolean force) { }", "A.java", TestTypeContexts.jdk());

        assertTrue(reading.succeeded(), "the fixture must parse: " + reading.failure());
        assertTrue(reading.parametersOf("process").contains("java.lang.String,boolean"),
            "parameter types must be resolved to their fully qualified form, was "
                + reading.parametersOf("process"));
        assertTrue(reading.parametersOf("process").contains("java.lang.String,boolean"),
            "and the canonical rendering is what is compared: "
                + reading.parametersOf("process"));
    }

    @Test
    @DisplayName("reads a no-arg method as an empty parameter list")
    void readsNoArgMethod() {
        ResolvedTypeReader.Reading reading =
            ResolvedTypeReader.read("void process() { }", "A.java", TestTypeContexts.jdk());

        assertTrue(reading.succeeded(), "the fixture must parse: " + reading.failure());
        assertTrue(reading.parametersOf("process").contains(""),
            "a no-arg method has an empty parameter list, not a missing one: "
                + reading.parametersOf("process"));
    }

    @Test
    @DisplayName("renders equivalent parameter spellings identically")
    void rendersEquivalentSpellingsIdentically() {
        ResolvedTypeReader.Reading shortForm = ResolvedTypeReader.read(
            "import java.util.List;\nvoid process(List<String> id) { }",
            "A.java", TestTypeContexts.jdk());
        ResolvedTypeReader.Reading longForm = ResolvedTypeReader.read(
            "void process(java.util.List<java.lang.String> id) { }",
            "A.java", TestTypeContexts.jdk());

        assertTrue(shortForm.succeeded() && longForm.succeeded(),
            "both spellings must parse: " + shortForm.failure() + " / " + longForm.failure());
        assertEquals(longForm.parametersOf("process"), shortForm.parametersOf("process"),
            "resolved types must make the two spellings comparable");
    }

    @Test
    @DisplayName("reports a parse failure instead of an empty method list")
    void reportsParseFailure() {
        // "could not tell" and "no methods here" must be different answers, or a
        // conflict silently becomes a non-conflict.
        ResolvedTypeReader.Reading reading = ResolvedTypeReader.read(
            "class A { void process(List<String> id", "A.java", TestTypeContexts.jdk());

        assertFalse(reading.succeeded(),
            "an unparsable version must report failure, not succeed with no methods");
        assertNotNull(reading.failure());
    }

    @Test
    @DisplayName("reports a missing type context rather than guessing")
    void reportsMissingTypeContext() {
        ResolvedTypeReader.Reading reading =
            ResolvedTypeReader.read("void process() { }", "A.java", null);

        assertFalse(reading.succeeded());
        assertTrue(reading.failure().contains("type context"), reading.failure());
    }

    @Test
    @DisplayName("reads the method names a version declares")
    void readsMethodNames() {
        assertTrue(ResolvedTypeReader.methodNamesOnly("void process(String id) { }")
            .contains("process"));
        assertTrue(ResolvedTypeReader.methodNamesOnly("// note\nvoid audit()\n{ }")
            .contains("audit"), "a comment and a next-line brace must not hide it");
    }

    @Test
    @DisplayName("recommends keeping both when the parameter lists differ")
    void recommendsKeepingBoth() {
        FixPath primary =
            resolver.getFixPaths(ConflictFixtures.sample(ConflictType.OVERLOAD_ADD)).get(0);
        assertEquals("Keep both overloads", primary.getRecommended());
    }
}
