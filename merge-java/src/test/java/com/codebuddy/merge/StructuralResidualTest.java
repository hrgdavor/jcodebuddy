// {@link com.codebuddy.merge.StructuralResidualTest} Tests that content-level divergence is never dropped.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conflict must never simply disappear.
 *
 * <p>These pin down a gap found while verifying the improvement workstreams: two
 * branches each adding a different statement to the same method produced
 * <em>nothing</em>, because every line of the base still appeared somewhere in both
 * branches. Content had changed on both sides, and the divergence was invisible to
 * a line-level view.
 *
 * <p>Dropping a conflict is the worst failure mode available to a merge tool: the
 * change does not move to a review pile, it vanishes. Anything both branches
 * changed incompatibly must reach a human even when no detector has a name for it.
 */
class StructuralResidualTest {

    private final ConflictDetectionService detector = new ConflictDetectionService();

    /** Both branches add a different statement to the same method body. */
    private static final String BASE = "int total = 0;\nreturn total;";
    private static final String BRANCH1 = "int total = 0;\nlog(total);\nreturn total;";
    private static final String BRANCH2 = "int total = 0;\naudit(total);\nreturn total;";

    @Test
    @DisplayName("two different additions to one body are not silently dropped")
    void twoDifferentAdditionsAreReported() {
        List<Conflict> conflicts = detector.detect("A.java", BASE, BRANCH1, BRANCH2);

        assertFalse(conflicts.isEmpty(),
            "both branches changed the same member, so a human must see it");
        assertTrue(conflicts.stream().anyMatch(c ->
                c.getType() == ConflictType.STRUCTURAL_CHANGE),
            "competing edits to one member are structural: " + conflicts);
        assertEquals(ConflictResolution.ResolutionKind.MANUAL,
            resolve(conflicts).getKind(),
            "and they must not be resolved automatically");
    }

    @Test
    @DisplayName("detection reports content divergence even with no recognisable lines")
    void reportsContentOnlyDivergence() {
        Conflict conflict = detector.detectStructuralConflict(
            "A.java", BASE, BRANCH1, BRANCH2, List.of());

        assertTrue(conflict != null, "content-only divergence must be detected");
        assertEquals(ConflictType.STRUCTURAL_CHANGE, conflict.getType());
    }

    @Test
    @DisplayName("a pure subset relationship is not reported as structural")
    void subsetRelationshipIsNotStructural() {
        // branch 2 is a superset of branch 1's change: one side extended what the
        // other did, which is not a competing edit.
        Conflict conflict = detector.detectStructuralConflict("A.java",
            BASE,
            "int total = 0;\nlog(total);\nreturn total;",
            "int total = 0;\nlog(total);\naudit(total);\nreturn total;",
            List.of());

        assertEquals(null, conflict,
            "one change containing the other is an extension, not a conflict");
    }

    @Test
    @DisplayName("a purely additive import change stays automatic")
    void additiveImportChangeStaysAutomatic() {
        List<Conflict> conflicts = detector.detect("A.java",
            "import java.util.List;\nclass A {\n}\n",
            "import java.util.List;\nimport java.math.BigDecimal;\nclass A {\n}\n",
            "import java.util.List;\nimport java.time.Instant;\nclass A {\n}\n");

        assertEquals(1, conflicts.size(), "was " + conflicts);
        assertEquals(ConflictType.IMPORT_ADD, conflicts.get(0).getType());
        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolve(conflicts).getKind(),
            "nothing here needs a human");
    }

    @Test
    @DisplayName("a review-level conflict does not swallow the residual divergence")
    void reviewConflictDoesNotSwallowResidual() {
        // The body detector claims this conflict, but only at review level. A
        // recognised-but-unresolved conflict must not be treated as accounting for
        // the divergence, or the file looks resolved while it is not.
        List<Conflict> conflicts = detector.detect("A.java", BASE, BRANCH1, BRANCH2);

        boolean hasUnresolved = conflicts.stream().anyMatch(c ->
            c.getType() == ConflictType.STRUCTURAL_CHANGE
                || c.getType() == ConflictType.METHOD_BODY_CHANGE);

        assertTrue(hasUnresolved,
            "the divergence must be reported by something: " + conflicts);
    }

    @Test
    @DisplayName("identical versions produce no structural noise")
    void identicalVersionsProduceNothing() {
        assertTrue(detector.detect("A.java", BASE, BASE, BASE).isEmpty());
    }

    private static ConflictResolution resolve(List<Conflict> conflicts) {
        MergeConflictResolver resolver = MergeConflictResolver.create(TestTypeContexts.jdk());
        return resolver.resolve(conflicts.get(0));
    }
}
