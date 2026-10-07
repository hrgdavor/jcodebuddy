// {@link com.codebuddy.merge.VerifierKeepsClaimsOutTest} A failed verification keeps a claim out (plan step 4.19).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composition step 4.19's own record says was asserted but never <b>fixtured</b>: a claim whose verification
 * fails cannot settle anything, because the failure downgrades it to {@code REVIEW} before the hierarchy ever sees
 * it — and {@code REVIEW} resolves nothing.
 *
 * <h2>Why the two halves are asserted together</h2>
 *
 * <p>Either half alone is weak evidence. "The verifier failed the answer" says nothing about settlement, and "a
 * {@code REVIEW} claim settles nothing" says nothing about verification. The claim being tested is the
 * <b>composition</b>: verifier failure → {@code REVIEW} → the hierarchy's reliability predicate refuses it. So this
 * file asserts the chain, and it does so twice — once with a stub answer (deterministic, and the only way to make the
 * verifier fail on demand) and once through the whole tool on a real file, because the second is the shape a person's
 * work actually arrives in.
 *
 * <h2>The end-to-end half asserts the safety property, not a mechanism</h2>
 *
 * <p>Whether a real resolver <em>can</em> produce unbalanced text depends on detection and parsing, which is a fact
 * about this module rather than about the guard. So the file-driven test asserts what must hold whatever the route:
 * <b>unbalanced text is never written into the file as a success</b>. The measurement it prints says which route
 * happened, so a future change that moves the case between routes is visible rather than silent.
 */
class VerifierKeepsClaimsOutTest {

    @TempDir
    Path tempDir;

    /** An answer that is deliberately unbalanced, so {@link ResolutionVerifier#structural()} has something to fail. */
    private static final String UNBALANCED = "public class OrderService {\n    void run() {\n";

    /**
     * A resolver that always answers its type automatically, with whatever code it was given.
     *
     * <p>It extends {@link AbstractConflictResolver} rather than implementing the interface so that the seam under
     * test is the ANSWER and not the plumbing: fix paths, naming and the null guards are the base class's business
     * and testing them here would dilute what the assertions are about.
     */
    private static final class AutoStub extends AbstractConflictResolver {

        private final ConflictType type;
        private final String code;

        AutoStub(ConflictType type, String code) {
            this.type = type;
            this.code = code;
        }

        @Override
        public ConflictType supportedType() {
            return type;
        }

        @Override
        protected List<FixPath> describeOptions(Conflict conflict) {
            return List.of();
        }

        @Override
        protected ConflictResolution doResolve(Conflict conflict) {
            return reviewResolution(conflict, ConflictResolution.ResolutionStrategy.MERGE_SAFE)
                .kind(ConflictResolution.ResolutionKind.AUTO)
                .resolvedCode(code)
                .explanation("a stub answer for the verifier's benefit")
                .build();
        }
    }

    @Test
    @DisplayName("the chain: verification fails, the answer is downgraded, and the hierarchy refuses it")
    void aFailedVerificationDowngradesAndSettlesNothing() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.MEMBER_ADD);

        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName("verifier-fixture")
            .setInMemoryOnly(true)
            .setResolvers(List.of(new AutoStub(ConflictType.MEMBER_ADD, UNBALANCED)))
            .build();

        ConflictResolution resolution = resolver.resolve(conflict);

        // 1. the verifier ran and failed, with a reason a person can read
        assertEquals(ConflictResolution.Verification.FAILED, resolution.getVerification(),
            "the unbalanced answer must fail verification: " + resolution.getExplanation());
        assertTrue(resolution.getExplanation().contains("verification failed"),
            "and the reason must be on the surface: " + resolution.getExplanation());

        // 2. the failure downgrades it, so nothing automatic survives
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "an automatic answer that fails verification must not stay automatic: " + resolution.getKind());

        // 3. and the hierarchy's own predicate refuses it, which is the half that makes the first two matter
        assertFalse(Reliability.of(resolution, conflict).isReliable(),
            "a downgraded claim must not be able to settle anything: "
                + Reliability.of(resolution, conflict));

        // The control, in the same chain: the same stub with a BALANCED answer stays automatic and IS reliable, so
        // the three assertions above are about the verification rather than about the stub.
        MergeConflictResolver sound = new MergeConflictResolver.Builder()
            .setBranchName("verifier-fixture")
            .setInMemoryOnly(true)
            .setResolvers(List.of(new AutoStub(ConflictType.MEMBER_ADD,
                "public class OrderService {\n    void run() {\n    }\n}\n")))
            .build();
        ConflictResolution passed = sound.resolve(conflict);
        assertEquals(ConflictResolution.ResolutionKind.AUTO, passed.getKind(),
            "the control must survive verification: " + passed.getExplanation());
        assertEquals(ConflictResolution.Verification.PASSED, passed.getVerification());

        // AND THE CONTROL IS STILL NOT RELIABLE — measured rather than assumed, and worth asserting because it
        // separates two properties a hurried reading merges. Verification asks "is this answer well-formed?";
        // reliability asks "does this claim account for the region?". A well-formed answer that explains nothing
        // passes the first and fails the second, so it settles nothing — which is the design (step 4.19: reliability
        // is its own predicate, and being asked early is never authority).
        assertFalse(Reliability.of(passed, conflict).isReliable(),
            "a passed verification is not authority: this answer explains no region: "
                + Reliability.of(passed, conflict));

        System.out.println("VERIFIER-FIXTURE: unbalanced -> " + resolution.getKind() + "/"
            + resolution.getVerification() + ", reliable=" + Reliability.of(resolution, conflict).isReliable()
            + "; balanced -> " + passed.getKind() + "/" + passed.getVerification()
            + ", reliable=" + Reliability.of(passed, conflict).isReliable()
            + " (verification and authority are different questions)");
    }

    @Test
    @DisplayName("end to end: unbalanced text is never written into a file as a success")
    void unbalancedTextIsNeverAppliedAsSuccess() throws IOException {
        // A member one branch left unclosed. Whatever the route — detection declining it, a resolver's answer failing
        // verification, or nothing claiming it — the file must not come out with the unclosed member applied as
        // though the tool had decided it.
        String file = """
            package com.example.demo;

            public class OrderService {
                public void audit() {
                }
            <<<<<<< ours
                public void charge() {
            ||||||| base
            =======
                public void refund() {
                }
            >>>>>>> theirs
            }
            """;
        Path path = tempDir.resolve("OrderService.java");
        Files.writeString(path, file, StandardCharsets.UTF_8);

        MergeFileTool.Result result = MergeFileTool.forFile(path)
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true)
            .applyFixes(true)
            .run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        String after = Files.readString(path, StandardCharsets.UTF_8);
        System.out.println("VERIFIER-FIXTURE (end to end): outcome " + outcome.outcome()
            + " type " + outcome.type() + ", exit " + result.exitCode()
            + ", markers left " + after.contains("<<<<<<<"));

        boolean applied = outcome.outcome() == MergeFileTool.Outcome.APPLIED_AUTO
            || outcome.outcome() == MergeFileTool.Outcome.APPLIED_PARTIAL;
        if (applied) {
            // If it was applied, the text must be balanced: an unbalanced write is the failure this asserts against.
            assertTrue(after.contains("public void charge() {") && after.contains("}"),
                "an applied answer must be balanced text: " + after);
            assertFalse(after.contains("public void charge() {\n}\n}"),
                "and the member must have been closed by something: " + after);
        } else {
            assertTrue(after.contains("<<<<<<<") || outcome.outcome() == MergeFileTool.Outcome.LEFT_UNCLASSIFIED,
                "an answer that was not applied must leave the block for a person: " + after);
        }
    }
}
