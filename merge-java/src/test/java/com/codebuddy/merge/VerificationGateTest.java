// {@link com.codebuddy.merge.VerificationGateTest} Tests for the pre-application verification gate.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module claims that an <em>automatic</em> resolution will not break the
 * build. These tests hold that claim to account: a resolver that emits broken
 * code must not produce an automatic resolution, and the reason must reach the
 * reviewer.
 */
class VerificationGateTest {

    private static final String WELL_FORMED = """
        package com.example;
        class A {
            void a() {
            }
        }
        """;

    private static Conflict conflict(String base, String b1, String b2) {
        return new Conflict(ConflictType.IMPORT_ADD, "A.java", "test", base, b1, b2);
    }

    private static ConflictResolution autoResolution(String resolvedCode) {
        return ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.IMPORT_ADD)
            .resolvedCode(resolvedCode)
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .kind(ConflictResolution.ResolutionKind.AUTO)
            .explanation("merged")
            .build();
    }

    // -------------------------------------------------------- balance checking

    @Test
    @DisplayName("accepts balanced code")
    void acceptsBalancedCode() {
        assertNull(ResolutionVerifier.checkBalanced(WELL_FORMED));
        assertNull(ResolutionVerifier.checkBalanced("void a() { if (x) { y(); } }"));
    }

    @Test
    @DisplayName("rejects an unclosed brace")
    void rejectsUnclosedBrace() {
        String problem = ResolutionVerifier.checkBalanced("class A {\n    void a() {\n");

        assertNotNull(problem);
        assertTrue(problem.contains("unclosed"), problem);
    }

    @Test
    @DisplayName("rejects a mismatched closing delimiter")
    void rejectsMismatchedDelimiter() {
        String problem = ResolutionVerifier.checkBalanced("void a() { return; )");

        assertNotNull(problem);
        assertTrue(problem.contains("expected") || problem.contains("unbalanced"), problem);
    }

    @Test
    @DisplayName("rejects an unterminated string or comment")
    void rejectsUnterminatedLiteral() {
        assertNotNull(ResolutionVerifier.checkBalanced("String s = \"abc;"));
        assertNotNull(ResolutionVerifier.checkBalanced("/* never closed"));
        assertNotNull(ResolutionVerifier.checkBalanced("char c = 'x;"));
    }

    @Test
    @DisplayName("ignores delimiters inside literals and comments")
    void ignoresDelimitersInLiteralsAndComments() {
        assertNull(ResolutionVerifier.checkBalanced("String s = \"}{)(\";"));
        assertNull(ResolutionVerifier.checkBalanced("// }}} \nint a = 1;"));
        assertNull(ResolutionVerifier.checkBalanced("/* }}} */ int a = 1;"));
        assertNull(ResolutionVerifier.checkBalanced("String s = \"a\\\"b}\";"));
    }

    // ------------------------------------------------------- scoping decisions

    @Test
    @DisplayName("recognises code that stands alone")
    void recognisesSelfContainedCode() {
        assertTrue(ResolutionVerifier.isSelfContained(WELL_FORMED));
        assertTrue(ResolutionVerifier.isSelfContained("import java.util.List;"));
        assertTrue(ResolutionVerifier.isSelfContained("public final class A {}"));
        assertTrue(ResolutionVerifier.isSelfContained("record Point(int x, int y) {}"));
        assertTrue(ResolutionVerifier.isSelfContained("interface A {}"));
        assertTrue(ResolutionVerifier.isSelfContained("enum E { A, B }"));

        assertFalse(ResolutionVerifier.isSelfContained("int total = 0;\ntotal += 1;"));
        assertFalse(ResolutionVerifier.isSelfContained("void a() {\n    x();\n}"));
    }

    @Test
    @DisplayName("skips the balance check for a fragment whose balance is undefined")
    void skipsBalanceCheckForFragment() {
        // A method body fragment: unbalanced in isolation, and that is expected.
        ConflictResolution fragment = autoResolution("        charge();\n    }");
        Conflict fragmentConflict = conflict("        audit();\n    }",
            "        charge();\n    }", "        refund();\n    }");

        ConflictResolution applied = ResolutionVerifier.structural()
            .apply(fragmentConflict, fragment);

        assertEquals(ConflictResolution.Verification.SKIPPED, applied.getVerification(),
            "a fragment must be skipped, not failed");
        assertEquals(ConflictResolution.ResolutionKind.AUTO, applied.getKind(),
            "a skip must not downgrade the resolution");
    }

    @Test
    @DisplayName("still checks a fragment when the inputs were balanced")
    void checksFragmentWhenInputsWereBalanced() {
        // The inputs are balanced whole files, so the resolution had the
        // opportunity to unbalance them and must be checked.
        Conflict balancedInputs = conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED);
        ConflictResolution unbalanced = autoResolution("class A { void a() {");

        ConflictResolution applied = ResolutionVerifier.structural()
            .apply(balancedInputs, unbalanced);

        assertEquals(ConflictResolution.Verification.FAILED, applied.getVerification());
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, applied.getKind());
    }

    // ------------------------------------------------------------- downgrading

    @Test
    @DisplayName("downgrades an unbalanced automatic resolution to review")
    void downgradesUnbalancedAutomaticResolution() {
        ConflictResolution applied = ResolutionVerifier.structural().apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED),
            autoResolution("package com.example;\nclass A {\n    void a() {\n"));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, applied.getKind(),
            "an unverified automatic resolution must not stay automatic");
        assertEquals(ConflictResolution.Verification.FAILED, applied.getVerification());
        assertTrue(applied.getExplanation().contains("verification failed"),
            "the explanation must say why: " + applied.getExplanation());
        assertTrue(applied.getAlternativePaths().stream()
                .anyMatch(path -> path.getJustification().contains("verification")),
            "a fix path must carry the reason: " + applied.getAlternativePaths());
    }

    @Test
    @DisplayName("downgrades a resolution that still contains conflict markers")
    void downgradesResolutionWithConflictMarkers() {
        ConflictResolution applied = ResolutionVerifier.structural().apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED),
            autoResolution("<<<<<<< ours\nint a = 1;\n=======\nint a = 2;\n>>>>>>> theirs\n"));

        assertEquals(ConflictResolution.Verification.FAILED, applied.getVerification());
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, applied.getKind());
    }

    @Test
    @DisplayName("downgrades an automatic resolution that produced no code")
    void downgradesEmptyResolution() {
        ConflictResolution applied = ResolutionVerifier.structural().apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED), autoResolution("   "));

        assertEquals(ConflictResolution.Verification.FAILED, applied.getVerification());
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, applied.getKind());
    }

    @Test
    @DisplayName("marks a passing automatic resolution as verified")
    void marksPassingResolutionVerified() {
        ConflictResolution applied = ResolutionVerifier.structural().apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED),
            autoResolution("import java.util.List;\nimport java.math.BigDecimal;"));

        assertEquals(ConflictResolution.Verification.PASSED, applied.getVerification());
        assertEquals(ConflictResolution.ResolutionKind.AUTO, applied.getKind());
        assertTrue(applied.isVerifiedAuto(),
            "a passed automatic resolution is the only kind safe to apply unchecked");
    }

    @Test
    @DisplayName("a verifier can never promote a resolution, only confirm or downgrade")
    void verifierCannotPromote() {
        ConflictResolution manual = ConflictResolution.manual(
                conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED))
            .build();

        ConflictResolution applied = ResolutionVerifier.permissive().apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED), manual);

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, applied.getKind(),
            "verification must not turn a manual conflict into an automatic one");
    }

    @Test
    @DisplayName("a permissive verifier leaves automatic resolutions alone")
    void permissiveVerifierAccepts() {
        ConflictResolution applied = ResolutionVerifier.permissive().apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED),
            autoResolution("class A { void a() { "));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, applied.getKind());
        assertEquals(ConflictResolution.Verification.PASSED, applied.getVerification());
    }

    @Test
    @DisplayName("a verifier that cannot run reports skipped rather than failing")
    void unusableVerifierReportsSkipped() {
        ResolutionVerifier broken = (conflict, resolution) -> null;

        ConflictResolution applied = broken.apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED),
            autoResolution(WELL_FORMED));

        assertEquals(ConflictResolution.Verification.SKIPPED, applied.getVerification());
        assertEquals(ConflictResolution.ResolutionKind.AUTO, applied.getKind(),
            "an unusable verifier must not silently downgrade everything");
    }

    @Test
    @DisplayName("does not verify a non-automatic resolution")
    void doesNotVerifyNonAutomaticResolutions() {
        ResolutionVerifier recorder = (conflict, resolution) -> {
            throw new AssertionError("a manual resolution must not be verified");
        };
        ConflictResolution manual = ConflictResolution.manual(
                conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED))
            .build();

        ConflictResolution applied = recorder.apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED), manual);

        assertSame(manual, applied, "a manual resolution must pass through untouched");
    }

    // -------------------------------------------------------- through resolver

    @Test
    @DisplayName("the resolver applies the gate to every automatic resolution")
    void resolverAppliesGate() {
        MergeConflictResolver defaultGate = MergeConflictResolver.create(TestTypeContexts.jdk());
        Conflict importConflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);

        ConflictResolution resolution = defaultGate.resolve(importConflict);

        assertNotNull(resolution.getVerification());
        assertTrue(resolution.getVerification() == ConflictResolution.Verification.PASSED
                || resolution.getVerification() == ConflictResolution.Verification.SKIPPED,
            "an import merge must verify or be explicitly skipped, was "
                + resolution.getVerification());
    }

    @TempDir
    Path tempDir;

    /**
     * A resolver with an isolated history directory.
     *
     * <p>Every test here needs its own, because a resolver that records a decision
     * would otherwise replay it into a later test through the shared default
     * history path - and the whole point of these tests is to observe what the
     * verification gate does to a <em>fresh</em> resolution.
     */
    private MergeConflictResolver.Builder resolverWith(ResolutionVerifier verifier) {
        return new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(tempDir.resolve("feature"))
            .setTypeContext(TestTypeContexts.jdk())
            .setVerifier(verifier);
    }

    @Test
    @DisplayName("a resolver can be built with a custom verifier")
    void resolverAcceptsCustomVerifier() {
        MergeConflictResolver resolver = resolverWith(ResolutionVerifier.permissive()).build();

        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.Verification.PASSED, resolution.getVerification());
    }

    @Test
    @DisplayName("a failing gate downgrades to review and offers the reason")
    void failingGateDowngradesThroughResolver() {
        ResolutionVerifier alwaysFails =
            (conflict, resolution) -> ResolutionVerifier.Result.failed("forced failure");
        MergeConflictResolver resolver = resolverWith(alwaysFails).build();

        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind());
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "a downgraded resolution must tell the reviewer what to do");
    }

    @Test
    @DisplayName("a strict verifier can require a complete compilation unit")
    void strictVerifierRequiresSelfContainedCode() {
        ResolutionVerifier strict = (conflict, resolution) ->
            ResolutionVerifier.isSelfContained(resolution.getResolvedCode())
                ? ResolutionVerifier.Result.passed()
                : ResolutionVerifier.Result.failed("not a complete compilation unit");

        ConflictResolution fragment = autoResolution("int total = 0;\ntotal += 1;");
        // The inputs are balanced, so the fragment is not skipped as undefined.
        Conflict balancedInputs = conflict("int total = 0;", "int total = 1;", "int total = 2;");

        ConflictResolution applied = strict.apply(balancedInputs, fragment);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, applied.getKind(),
            "a strict policy must be able to reject fragment output");
        assertTrue(applied.getAlternativePaths().stream()
                .anyMatch(path -> path.getJustification().contains("not a complete")),
            "the policy's reason must reach the reviewer: " + applied.getAlternativePaths());
    }

    @Test
    @DisplayName("a strict policy leaves a complete compilation unit alone")
    void strictVerifierAcceptsCompleteUnit() {
        ResolutionVerifier strict = (conflict, resolution) ->
            ResolutionVerifier.isSelfContained(resolution.getResolvedCode())
                ? ResolutionVerifier.Result.passed()
                : ResolutionVerifier.Result.failed("not a complete compilation unit");

        ConflictResolution applied = strict.apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED),
            autoResolution(WELL_FORMED));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, applied.getKind());
        assertEquals(ConflictResolution.Verification.PASSED, applied.getVerification());
    }

    @Test
    @DisplayName("the gate verifies automatic resolutions only")
    void gateOnlyVerifiesAutomaticResolutions() {
        // A review or manual resolution is already surfaced to a human, so
        // verifying it would add nothing and would overwrite the resolver's own
        // reasoning.
        ResolutionVerifier alwaysFails =
            (conflict, resolution) -> ResolutionVerifier.Result.failed("forced");
        ConflictResolution review = ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.METHOD_BODY_CHANGE)
            .resolvedCode("int total = 0;")
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.MERGE_SAFE)
            .kind(ConflictResolution.ResolutionKind.REVIEW)
            .explanation("resolver reasoning")
            .build();

        ConflictResolution applied = alwaysFails.apply(
            conflict(WELL_FORMED, WELL_FORMED, WELL_FORMED), review);

        assertSame(review, applied,
            "a review resolution must pass through the gate untouched");
    }
    @Test
    @DisplayName("an import list counts as self-contained because imports stand alone")
    void importListIsSelfContained() {
        assertTrue(ResolutionVerifier.isSelfContained("import java.util.List;"));
        assertTrue(ResolutionVerifier.isSelfContained(
            "import java.util.List;\nimport java.math.BigDecimal;"));
        assertFalse(ResolutionVerifier.isSelfContained("int total = 0;\ntotal += 1;"));
    }

    @Test
    @DisplayName("verification is recorded on every resolution path")
    void verificationRecordedOnEveryPath() {
        MergeConflictResolver resolver = MergeConflictResolver.create(TestTypeContexts.jdk());

        for (ConflictType type : List.of(ConflictType.IMPORT_ADD, ConflictType.STRUCTURAL_CHANGE,
                ConflictType.VARIABLE_RENAME)) {
            ConflictResolution resolution = resolver.resolve(ConflictFixtures.sample(type));
            assertNotNull(resolution.getVerification(),
                "verification status missing for " + type);
        }
    }
}
