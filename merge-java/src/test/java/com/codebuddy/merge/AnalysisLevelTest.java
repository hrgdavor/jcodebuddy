// {@link com.codebuddy.merge.AnalysisLevelTest} Pins the evidence scale and the declarations that back it.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence scale is only worth having if the declarations behind it are true, so these tests hold
 * the two halves that can be checked mechanically: the ordering itself, and the rule that a resolution
 * never records stronger evidence than the resolver it came from can reach.
 *
 * <p>What cannot be checked here is whether a resolver's declared maximum is <em>honest</em> — that a
 * resolver reading one line of text does not claim to have resolved types. That is a claim a reviewer
 * reads in {@code docs/resolvers/README.md} beside the resolver, which is where it belongs.
 */
class AnalysisLevelTest {

    @Test
    @DisplayName("the scale orders evidence, and equal evidence is never stronger")
    void scaleOrdersEvidence() {
        assertTrue(AnalysisLevel.PROJECT_TYPES.isStrongerThan(AnalysisLevel.PLATFORM_TYPES));
        assertTrue(AnalysisLevel.PLATFORM_TYPES.isStrongerThan(AnalysisLevel.STRUCTURE));
        assertTrue(AnalysisLevel.STRUCTURE.isStrongerThan(AnalysisLevel.TEXT_FILE));
        assertTrue(AnalysisLevel.TEXT_FILE.isStrongerThan(AnalysisLevel.TEXT_LOCAL));
        // Plan step 4.11's level, and it is asserted in BOTH directions: a word-level comparison says more than a
        // line set and less than anything that reads outside the block. One direction alone would leave it free to
        // drift on the other side, which is where a "better" answer would quietly become a licence.
        assertTrue(AnalysisLevel.TEXT_INTRALINE.isStrongerThan(AnalysisLevel.TEXT_LOCAL));
        assertTrue(AnalysisLevel.TEXT_FILE.isStrongerThan(AnalysisLevel.TEXT_INTRALINE));
        assertFalse(AnalysisLevel.TEXT_LOCAL.isStrongerThan(AnalysisLevel.TEXT_INTRALINE));
        assertFalse(AnalysisLevel.TEXT_INTRALINE.isStrongerThan(AnalysisLevel.TEXT_FILE));

        // Strictness is the whole point: two analyses of the same strength disagreeing is the case a
        // human settles, so a level must not outrank its own equal.
        for (AnalysisLevel level : AnalysisLevel.values()) {
            assertFalse(level.isStrongerThan(level), level + " must not outrank itself");
            assertTrue(level.isAtLeast(level), level + " is at least itself");
        }

        // And nothing is stronger than the strongest, which is what keeps one winner at most.
        for (AnalysisLevel level : AnalysisLevel.values()) {
            assertTrue(AnalysisLevel.PROJECT_TYPES.isAtLeast(level),
                AnalysisLevel.PROJECT_TYPES + " must dominate " + level);
        }
    }

    @Test
    @DisplayName("a resolution never records more evidence than its resolver can reach")
    void resolutionsNeverExceedTheirResolver() {
        for (ConflictResolver resolver : ConflictResolvers.defaultResolvers()) {
            ConflictType type = resolver.supportedType();
            ConflictResolution resolution = resolver.resolve(ConflictFixtures.sample(type));

            assertTrue(resolver.maxAnalysisLevel().isAtLeast(resolution.getAnalysisLevel()),
                type + ": " + resolver.getClass().getSimpleName() + " declares at most "
                    + resolver.maxAnalysisLevel() + " but recorded " + resolution.getAnalysisLevel());
        }
    }

    @Test
    @DisplayName("the intra-line level has a producer: words when they were needed, line sets when they were not")
    void theIntraLineLevelIsRecordedOnlyWhenWordsWereRead() {
        // Plan step 4.11's declaration half, asserted on the resolver that can now reach the level — and asserted in
        // BOTH directions, because a level is a claim about what was read and one direction alone would let the
        // declaration become a licence.
        MethodBodyChangeConflictResolver resolver = new MethodBodyChangeConflictResolver();

        // The words case: both sides edited one statement line, each deleting a different word, so the words neither
        // removed are the answer. A statement-set comparison can only say "the same statement changed twice".
        Conflict atWordLevel = new Conflict(ConflictType.METHOD_BODY_CHANGE, "A.java", "sample",
            "int total = a + b + c;\n", "int total = b + c;\n", "int total = a + b;\n");
        ConflictResolution fromWords = resolver.resolve(atWordLevel);
        assertEquals(AnalysisLevel.TEXT_INTRALINE, fromWords.getAnalysisLevel(),
            "the answer needed the word comparison, and the record says so: " + fromWords.getExplanation());
        assertFalse(fromWords.getResolvedCode().isBlank(),
            "and it carries the composition: " + fromWords.getResolvedCode());
        assertTrue(resolver.maxAnalysisLevel().isAtLeast(fromWords.getAnalysisLevel()),
            "the declaration must cover the record: " + resolver.maxAnalysisLevel());

        // The fall-back case: the pass refuses (two different insertions at one point), so the statement-set answer
        // stands — and it records the LINE level, because that is all it read. Recording TEXT_INTRALINE here would be
        // claiming a comparison that never happened.
        Conflict notComposable = new Conflict(ConflictType.METHOD_BODY_CHANGE, "A.java", "sample",
            "int total = computeTotal();\n", "int total = computeGrand();\n", "int total = computeNet();\n");
        ConflictResolution fellBack = resolver.resolve(notComposable);
        assertEquals(AnalysisLevel.TEXT_LOCAL, fellBack.getAnalysisLevel(),
            "a resolution that fell back to line sets records TEXT_LOCAL, not TEXT_INTRALINE: "
                + fellBack.getExplanation());
    }

    @Test
    @DisplayName("a conflict that never decides objects from its resolver's own basis")
    void undecidedConflictsObjectFromTheirOwnBasis() {
        // The two resolvers that never resolve anything: how easily their objection is outranked is
        // exactly what their declared level states, so the manual fallback must carry it. These two
        // are named rather than looped over because the assertion is about them - a resolver that
        // *declines for a reason* (an overload clash with no context) records the weaker evidence it
        // actually had, which is the distinction between the declaration and the record.
        assertEquals(AnalysisLevel.STRUCTURE,
            new StructuralChangeConflictResolver()
                .resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE))
                .getAnalysisLevel(),
            "the residual objects from recognised members, so a line-reading claim cannot outrank it");
        assertEquals(AnalysisLevel.TEXT_LOCAL,
            new ApiIncompatibilityConflictResolver()
                .resolve(ConflictFixtures.sample(ConflictType.API_INCOMPATIBILITY))
                .getAnalysisLevel(),
            "the API check compares one line of text, so a stronger answer may outrank it");
    }

    @Test
    @DisplayName("a resolver that declines records the weaker evidence it actually had")
    void decliningRecordsWhatWasActuallyReached() {
        // No context at all, so nothing was resolved: the record must not claim the maximum just
        // because the resolver is capable of it.
        ConflictResolution declined = new OverloadAddConflictResolver()
            .resolve(ConflictFixtures.sample(ConflictType.OVERLOAD_ADD));

        assertEquals(AnalysisLevel.PLATFORM_TYPES, declined.getAnalysisLevel(),
            "without a context nothing about the project could have been resolved");
        assertEquals(AnalysisLevel.PROJECT_TYPES,
            new OverloadAddConflictResolver().maxAnalysisLevel(),
            "while the resolver can still reach further when a classpath is supplied");
    }

    @Test
    @DisplayName("only a caller-supplied entry separates the two type levels")
    void projectEntriesAreWhatSeparateTheTwoTypeLevels() {
        Path root = Path.of(".").toAbsolutePath();
        assertFalse(TypeContext.withRuntimeClasspath(root).hasProjectEntries(),
            "the JVM's own classpath is the platform, not the project");
        assertTrue(TypeContext.withRuntimeClasspathAnd(root, List.of(Path.of("build/classes")))
                .hasProjectEntries(),
            "an entry the platform does not carry is the project's");
    }

    @Test
    @DisplayName("a type change records which classpath it actually had")
    void typeChangeRecordsTheClasspathItActuallyHad() {
        TypeChangeConflictResolver resolver = new TypeChangeConflictResolver();
        Path root = Path.of(".").toAbsolutePath();

        ConflictResolution onThePlatform = resolver.resolve(
            ConflictFixtures.sample(ConflictType.TYPE_CHANGE)
                .withTypeContext(TypeContext.withRuntimeClasspath(root)));
        ConflictResolution withTheProject = resolver.resolve(
            ConflictFixtures.sample(ConflictType.TYPE_CHANGE)
                .withTypeContext(TypeContext.withRuntimeClasspathAnd(root,
                    List.of(Path.of("build/classes")))));

        assertEquals(AnalysisLevel.PLATFORM_TYPES, onThePlatform.getAnalysisLevel(),
            "the platform alone cannot answer a question about the project's own types");
        assertEquals(AnalysisLevel.PROJECT_TYPES, withTheProject.getAnalysisLevel(),
            "and with the project's entries the same resolver reaches further");
        assertEquals(AnalysisLevel.PROJECT_TYPES, resolver.maxAnalysisLevel(),
            "the declared maximum is the stronger of the two, and the record says which was used");
    }
}