// {@link com.codebuddy.merge.ConflictShapeTest} The shape of a change, beside the domain type (plan step 4.12).
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.jetbrains.text.ComparisonPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conflict <b>shape</b> — inserted, deleted, modified, conflicting — computed by the ported classifier (unified
 * plan step 4.12).
 *
 * <h2>Why the two taxonomies are both needed, in one line each</h2>
 *
 * <p>{@link ConflictType} says what a conflict is <em>about</em>, and cannot say "both sides inserted here".
 * {@link ConflictShape} says the shape, and cannot say "this is a type widening". A reviewer needs both, and the
 * assertion that keeps them apart is that adding the shape changed no domain type.
 *
 * <h2>What the shape must never be able to do</h2>
 *
 * <p>Promote anything. It describes a change; it never decides one. {@link #aShapeNeverMakesAManualResolutionApplied()}
 * is that boundary as a test, and it is asserted on the decision rather than on the shape's own API, because the
 * shape is only dangerous where it is believed.
 */
class ConflictShapeTest {

    private static final ComparisonPolicy POLICY = ComparisonPolicy.DEFAULT;

    @Test
    @DisplayName("both sides inserting the SAME text is an insertion; different texts are a conflict")
    void bothSidesInserting() {
        // The distinction R6 turns on, and the reason an insertion at an empty base is not automatically one shape:
        // two different insertions at one point have no more correct order, so the shape says a person decides.
        ConflictShape same = ConflictShape.of("", "added\n", "added\n", POLICY, true);
        assertEquals(ConflictShape.INSERTED, same);
        assertTrue(same.isInsertion());

        ConflictShape different = ConflictShape.of("", "ours added\n", "theirs added\n", POLICY, true);
        assertEquals(ConflictShape.CONFLICT, different);
        assertTrue(different.isConflict());
        assertTrue(same.isOneSided(), "an agreed insertion is a change only one side needed to make");
    }

    @Test
    @DisplayName("one side inserting with the other unchanged is an insertion - the mechanical case")
    void oneSidedInsertionIsAnInsertion() {
        // The general form of the defect steps 4.5 and 4.6 measured: a line comparison calls this a structural
        // change and a shape calls it mechanical.
        ConflictShape shape = ConflictShape.of("a\nc\n", "a\nB\nc\n", "a\nc\n", POLICY, true);

        assertEquals(ConflictShape.INSERTED, shape);
    }

    @Test
    @DisplayName("both sides removing the base's lines is a deletion")
    void bothSidesRemovingIsADeletion() {
        ConflictShape shape = ConflictShape.of("a\ngone\nc\n", "a\nc\n", "a\nc\n", POLICY, true);

        assertEquals(ConflictShape.DELETED, shape);
        assertFalse(shape.isInsertion());
    }

    @Test
    @DisplayName("one side changing the base's line is a modification")
    void oneSidedChangeIsAModification() {
        ConflictShape shape = ConflictShape.of("a\nb\nc\n", "a\nB\nc\n", "a\nb\nc\n", POLICY, true);

        assertEquals(ConflictShape.MODIFIED, shape);
        assertTrue(shape.isOneSided());
    }

    @Test
    @DisplayName("both sides changing the same line differently is a conflict")
    void bothSidesChangingDifferentlyIsAConflict() {
        ConflictShape shape = ConflictShape.of("a\nb\nc\n", "a\nOURS\nc\n", "a\nTHEIRS\nc\n", POLICY, true);

        assertEquals(ConflictShape.CONFLICT, shape);
        assertTrue(shape.isConflict());
        assertFalse(shape.isOneSided(), "a conflict is what needs a person, not what a shape may settle");
    }

    @Test
    @DisplayName("both sides making the same change is a modification, not a conflict")
    void identicalChangesAreNotAConflict() {
        // Identical changes are not a disagreement, which is the distinction the whole merge model turns on.
        ConflictShape shape = ConflictShape.of("a\nb\nc\n", "a\nB\nc\n", "a\nB\nc\n", POLICY, true);

        assertEquals(ConflictShape.MODIFIED, shape);
        assertFalse(shape.isConflict());
    }

    @Test
    @DisplayName("a base-less block records an unknown shape rather than guessing")
    void aBaseLessBlockIsUnknown() {
        // git's default merge style carries no base, and with no base "both sides inserted" and "one side inserted
        // while the other deleted" are the same two texts. A wrong shape would be read as evidence.
        assertEquals(ConflictShape.UNKNOWN, ConflictShape.of("", "ours\n", "theirs\n", POLICY, false));
        assertFalse(ConflictShape.UNKNOWN.isOneSided());
        assertFalse(ConflictShape.UNKNOWN.isConflict());
    }

    @Test
    @DisplayName("sides that agree have no shape, because there is no change to describe")
    void agreeingSidesHaveNoShape() {
        assertEquals(ConflictShape.UNKNOWN, ConflictShape.of("a\nb\n", "a\nb\n", "a\nb\n", POLICY, true));
    }

    @Test
    @DisplayName("detection gives every conflict a shape, and records unknown where it cannot")
    void detectionAttributesTheShape() {
        // The pipeline, not just the factory: a shape nobody computes is a shape nobody can read. And the base-less
        // case is asserted on the same run, because that is the branch where guessing would be tempting and wrong.
        ConflictDetectionService detector = new ConflictDetectionService();

        List<Conflict> withBase = detector.detect("A.java", "a\nb\nc\n", "a\nOURS\nc\n", "a\nTHEIRS\nc\n",
            null, true);
        assertFalse(withBase.isEmpty());
        assertTrue(withBase.stream().noneMatch(conflict -> conflict.getShape() == ConflictShape.UNKNOWN),
            "every conflict of a diff3 block has a shape: " + withBase.stream()
                .map(conflict -> conflict.getType() + "=" + conflict.getShape()).toList());
        assertTrue(withBase.stream().anyMatch(conflict -> conflict.getShape() == ConflictShape.CONFLICT),
            "both sides changing one line differently is a conflict: " + withBase.stream()
                .map(conflict -> conflict.getType() + "=" + conflict.getShape()).toList());

        List<Conflict> withoutBase = detector.detect("A.java", "", "a\nOURS\nc\n", "a\nTHEIRS\nc\n",
            null, false);
        assertFalse(withoutBase.isEmpty());
        assertTrue(withoutBase.stream().allMatch(conflict -> conflict.getShape() == ConflictShape.UNKNOWN),
            "git's default merge style carries no base, so no shape may be guessed");
    }

    @Test
    @DisplayName("the shape travels with the conflict through the copies the tool makes")
    void theShapeSurvivesTheCopies() {
        // MergeFileTool re-stamps every detected conflict with its block's file region, so a shape dropped by a copy
        // helper would be erased by the very next step and the report would say "unknown" for every conflict.
        Conflict conflict = new Conflict(ConflictType.MEMBER_ADD, "A.java", "sample",
            "a\n", "b\n", "c\n").withShape(ConflictShape.INSERTED);

        assertEquals(ConflictShape.INSERTED, conflict.withRegion(Region.spanning(4, 9)).getShape());
        assertEquals(ConflictShape.INSERTED, conflict.withTypeContext(TestTypeContexts.jdk()).getShape());
        assertEquals(ConflictShape.INSERTED, conflict.withFilePath("B.java").getShape());
        assertEquals(ConflictShape.UNKNOWN,
            new Conflict(ConflictType.MEMBER_ADD, "A.java", "sample", "a\n", "b\n", "c\n").getShape(),
            "a hand-built conflict says unknown rather than inventing a shape");
    }

    @Test
    @DisplayName("a shape never makes a manual resolution applied")
    void aShapeNeverMakesAManualResolutionApplied() {
        // The boundary: the shape informs what is claimed and how it is explained, and it never promotes a REVIEW
        // or a MANUAL answer into application. DESIGN_NEVER_AUTO_RESOLVED.md's rule is unchanged by this step, and
        // the assertion belongs on the decision path because that is where a shape could be believed.
        Conflict conflict = new Conflict(ConflictType.STRUCTURAL_CHANGE, "A.java", "sample",
            "a\nc\n", "a\nB\nc\n", "a\nc\n").withShape(ConflictShape.INSERTED);
        ConflictResolution manual = ConflictResolution.manual(conflict)
            .resolvedCode("a\nB\nc\n")
            .build();

        assertEquals(ConflictShape.INSERTED, conflict.getShape(),
            "the shape is recorded on the conflict, which is where it is computed and reported");
        assertTrue(manual.requiresHumanDecision(), "a manual answer stays manual whatever the shape says");
        assertFalse(manual.isVerifiedAuto(), "and a shape is not a verification");
        assertFalse(manual.isReplayable(), "nor a decision somebody made");
    }
}
