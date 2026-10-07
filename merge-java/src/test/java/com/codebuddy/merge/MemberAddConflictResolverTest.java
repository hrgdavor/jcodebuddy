// {@link com.codebuddy.merge.MemberAddConflictResolverTest} Tests keeping the distinct members both branches added.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this resolver may and may not keep.
 *
 * <p>The case worth the most attention is the one that made it necessary: two branches appending a
 * member at the same point, where the two sides also carry the member the base already declared.
 * Keeping both sides is the answer, and doing it by concatenation repeats that shared member and
 * produces code the compiler rejects — so the tests below count the shared member rather than only
 * checking that both additions survived.
 */
class MemberAddConflictResolverTest {

    private final MemberAddConflictResolver resolver = new MemberAddConflictResolver();

    //#region keeps-both-distinct-members
    @Test
    @DisplayName("keeps both additions, and the shared member exactly once")
    void keepsBothDistinctMembers() {
        ConflictResolution resolution = resolver.resolve(
            ConflictFixtures.sample(ConflictType.MEMBER_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertEquals(ConflictResolution.ResolutionStrategy.KEEP_BOTH,
            resolution.getResolutionStrategy());
        assertEquals(AnalysisLevel.STRUCTURE, resolution.getAnalysisLevel(),
            "two additions colliding is a question about declared members, so the answer rests on "
                + "structure rather than on text");

        String merged = resolution.getResolvedCode();
        assertTrue(merged.contains("void charge()"), "branch 1's addition survives: " + merged);
        assertTrue(merged.contains("void refund()"), "branch 2's addition survives: " + merged);
        assertTrue(SideUnion.keeps(merged, resolution.getBranch1Code()), merged);
        assertTrue(SideUnion.keeps(merged, resolution.getBranch2Code()), merged);

        // The member both sides carried appears once, because both sides carried it: appending the
        // sides would declare it twice and the merged class would not compile.
        assertEquals(1, occurrences(merged, "void audit()"),
            "the shared member must not be repeated: " + merged);
        assertEquals(3, DeclarationScanner.declarationsIn(merged).size(),
            "audit(), charge() and refund(), each once: " + merged);
    }
    //#endregion

    //#region refuses-a-shared-signature
    @Test
    @DisplayName("refuses two additions with the same signature - only one member can exist")
    void refusesASharedSignature() {
        // Same name and parameter list, different return type: the signatures collide, so this is the
        // overload resolver's question (or a human's), not an additive union.
        Conflict collision = new Conflict(ConflictType.MEMBER_ADD, "C.java", "collision",
            "void audit() { }",
            "void audit() { }\nvoid charge() { }",
            "void audit() { }\nint charge() { return 0; }");

        ConflictResolution resolution = resolver.resolve(collision);

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind(),
            "a collision is refused rather than guessed: " + resolution.getExplanation());
        assertTrue(resolution.requiresHumanDecision());
    }
    //#endregion

    @Test
    @DisplayName("declines when one side added nothing")
    void declinesWhenOneSideAddedNothing() {
        Conflict oneSided = new Conflict(ConflictType.MEMBER_ADD, "C.java", "one side",
            "void audit() { }",
            "void audit() { }\nvoid charge() { }",
            "void audit() { }");

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolver.resolve(oneSided).getKind());
    }

    //#region union-needs-a-shared-prefix
    @Test
    @DisplayName("the union is refused when the sides share more than a prefix")
    void unionIsRefusedWhenTheSidesShareMoreThanAPrefix() {
        // The shared member sits after both additions, so it is in both remainders and the union would
        // declare it twice. Refusing is the safe answer: the block becomes a human's decision instead
        // of code that does not compile.
        assertNull(SideUnion.of(
            "void charge() { }\nvoid shared() { }",
            "void refund() { }\nvoid shared() { }"));

        // And the shape this resolver exists for is accepted, with the shared member taken once.
        String union = SideUnion.of(
            "void audit() { }\nvoid charge() { }",
            "void audit() { }\nvoid refund() { }");
        assertTrue(union != null && union.contains("void charge()") && union.contains("void refund()"),
            union);
        assertEquals(1, occurrences(union, "void audit()"), union);
    }
    //#endregion

    //#region detector-requires-a-known-base
    @Test
    @DisplayName("detection requires a base side, so an addition is never confused with a deletion")
    void detectionRequiresAKnownBase() {
        ConflictDetectionService detector = new ConflictDetectionService();
        String branch1 = "void audit() { }\nvoid charge() { }";
        String branch2 = "void audit() { }\nvoid refund() { }";

        assertTrue(detector.detectMemberAddConflicts("C.java", "", branch1, branch2).isEmpty(),
            "without a base, 'both branches added it' cannot be told from 'one branch added it and "
                + "the other deleted it'");

        assertEquals(1, detector.detectMemberAddConflicts("C.java", "void audit() { }",
            branch1, branch2).size(),
            "with one, the two additions are recognised");

        assertEquals(0, detector.detectMemberAddConflicts("C.java", "void audit() { }\nvoid legacy() { }",
            "void audit() { }\nvoid charge() { }",
            "void audit() { }\nvoid refund() { }").size(),
            "a member removed on one side is structural, and stays a human's");

        assertFalse(detector.detectMemberAddConflicts("C.java", "void audit() { }",
            branch1, branch1).size() > 0,
            "identical additions are not two additions");
    }
    //#endregion

    @Test
    @DisplayName("an insertion at an empty but known base is recognised")
    void insertionAtAnEmptyKnownBaseIsRecognised() {
        ConflictDetectionService detector = new ConflictDetectionService();
        String branch1 = "public int charge() {\n    return 1;\n}";
        String branch2 = "public int refund() {\n    return 2;\n}";

        // A diff3 hunk whose base section is present and empty says the base had nothing in this
        // region: both branches inserted. That is the shape a real merge produces for two adjacent
        // additions, and the one this detector exists for.
        assertEquals(1, detector.detectMemberAddConflicts("C.java", "", branch1, branch2, true).size(),
            "a known, empty base means both sides inserted");

        // No base section at all is a different statement — the base is unknown — and there "both
        // branches added it" cannot be told from "one branch added it and the other deleted it".
        assertEquals(0, detector.detectMemberAddConflicts("C.java", "", branch1, branch2, false).size(),
            "an unknown base is never read as an empty one");
        assertEquals(0, detector.detectMemberAddConflicts("C.java", "", branch1, branch2).size(),
            "and the convenient overload defaults to the conservative reading");
    }

    @Test
    @DisplayName("a local variable is not a member, however much it looks like a field")
    void aLocalVariableIsNotAMember() {
        // Both bodies keep and extend the same method, and each adds a local. Were a local read as a
        // field, "keep both" would concatenate two competing bodies and call it a member addition.
        assertEquals(0, new ConflictDetectionService().detectMemberAddConflicts("C.java",
            "void audit() {\n    int total = 0;\n}",
            "void audit() {\n    int total = 0;\n    int ours = 1;\n}",
            "void audit() {\n    int total = 0;\n    int theirs = 2;\n}").size(),
            "a declaration below the method's own depth is a local, not a member");
    }

    @Test
    @DisplayName("a bare insertion is read from its modifiers, and only when they prove it")
    void aBareInsertionIsReadFromItsModifiers() {
        ConflictDetectionService detector = new ConflictDetectionService();

        // A local variable cannot be declared private, so the modifier is proof of a member even where
        // no method or type in the fragment can establish the declaration level.
        assertEquals(1, detector.detectMemberAddConflicts("C.java", "",
            "    private final int chargeCount = 1;",
            "    private final int refundCount = 2;",
            true).size(),
            "an access modifier is evidence that the line is a member");

        // Without one there is nothing to tell a field from a local, so nothing is claimed.
        assertEquals(0, detector.detectMemberAddConflicts("C.java", "",
            "    int chargeCount = 1;",
            "    int refundCount = 2;",
            true).size(),
            "a package-private field in a bare insertion is declined, not guessed");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    @Test
    @DisplayName("offers keeping both, and the manual escape hatch")
    void offersKeepingBoth() {
        List<FixPath> paths = resolver.getFixPaths(ConflictFixtures.sample(ConflictType.MEMBER_ADD));

        assertFalse(paths.isEmpty());
        assertTrue(paths.stream().anyMatch(path -> path.getDescription().contains("Keep both")),
            paths.toString());
        assertTrue(paths.stream().anyMatch(path -> path.getDescription().toLowerCase().contains("by hand")),
            "a resolver that can refuse must always offer a human the way out: " + paths);
    }
}