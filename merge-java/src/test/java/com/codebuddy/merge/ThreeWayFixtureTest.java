// {@link com.codebuddy.merge.ThreeWayFixtureTest} Tests driven by real source files on disk.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merge cases held as complete, compilable Java files rather than fragments.
 *
 * <p>The case these exercise is the one a text diff gets wrong. Ours adds
 * {@code java.time.Instant}; theirs removes {@code java.util.Set}. Compared with each
 * other the import blocks differ, so a two-way diff sees a clash over the import area
 * and either reports a conflict or splices the block into something that does not
 * compile. Compared against the base it is plain that the changes are independent.
 */
class ThreeWayFixtureTest {

    private static final String CASE_NAME = "import-add-both";

    /**
     * A case where the branches genuinely disagree about a removal - the only import
     * situation where the clash policy has anything to decide.
     */
    private static final String DISPUTED_CASE = "import-add-remove-same";

    private final ThreeWayFixture fixture = ThreeWayFixture.load(CASE_NAME);
    private final ImportConflictResolver resolver = new ImportConflictResolver();

    @Test
    @DisplayName("the fixture is complete on disk, including both diffs")
    void fixtureIsComplete() {
        assertTrue(Files.isDirectory(fixture.directory()), fixture.directory().toString());
        assertTrue(Files.isRegularFile(fixture.directory().resolve("base/PaymentProcessor.java.txt")));
        assertTrue(Files.isRegularFile(fixture.directory().resolve("ours/PaymentProcessor.java.txt")));
        assertTrue(Files.isRegularFile(fixture.directory().resolve("theirs/PaymentProcessor.java.txt")));
        assertTrue(Files.isRegularFile(fixture.directory().resolve("ours.diff")),
            "each branch ships the diff for a human to read");
        assertTrue(Files.isRegularFile(fixture.directory().resolve("theirs.diff")));

        // Full files, not hunks: each must be a complete compilable unit.
        for (String source : List.of(fixture.base(), fixture.ours(), fixture.theirs())) {
            assertTrue(source.contains("package com.example.payments;"), "a real file has a package");
            assertTrue(source.contains("public class PaymentProcessor"), "and a type declaration");
            assertTrue(source.strip().endsWith("}"), "and closes");
        }
    }

    @Test
    @DisplayName("the change is read as an addition versus a removal, not as a clash")
    void readsAdditionVersusRemoval() {
        ImportChange ours = fixture.oursImportChange().orElseThrow();
        ImportChange theirs = fixture.theirsImportChange().orElseThrow();

        assertEquals(List.of("java.time.Instant"), names(ours.added()));
        assertTrue(ours.removed().isEmpty(), "ours removed nothing, was " + names(ours.removed()));

        assertEquals(List.of("java.util.Set"), names(theirs.removed()));
        assertTrue(theirs.added().isEmpty(), "theirs added nothing, was " + names(theirs.added()));

        assertFalse(ours.clashesWith(theirs),
            "adding one import while removing a different one does not clash");
        assertFalse(ours.agreesWith(theirs), "but they are not the same change either");
    }

    //#region computed-change-agrees-with-the-diff
    @Test
    @DisplayName("the computed change agrees with the diff a human would read")
    void computedChangeAgreesWithTheDiff() {
        Set<String> oursAddedInDiff = ThreeWayFixture.addedImportsInDiff(fixture.oursDiff());
        Set<String> theirsRemovedInDiff = ThreeWayFixture.removedImportsInDiff(fixture.theirsDiff());

        assertFalse(oursAddedInDiff.isEmpty(), "the fixture diff must state what ours added");
        assertFalse(theirsRemovedInDiff.isEmpty(), "the fixture diff must state what theirs removed");

        Set<String> oursAddedComputed = fixture.oursImportChange().orElseThrow()
            .added().stream().map(ref -> ref.rendered()).collect(Collectors.toSet());
        Set<String> theirsRemovedComputed = fixture.theirsImportChange().orElseThrow()
            .removed().stream().map(ref -> ref.rendered()).collect(Collectors.toSet());

        assertEquals(oursAddedInDiff, oursAddedComputed,
            "the structurally computed addition must match the documented one");
        assertEquals(theirsRemovedInDiff, theirsRemovedComputed,
            "and so must the documented removal");
    }
    //#endregion

    //#region composes-independent-changes
    @Test
    @DisplayName("composes the two independent changes instead of reporting a conflict")
    void composesIndependentChanges() {
        ConflictResolution resolution = resolver.resolve(fixture.conflict(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "an addition and a removal of different imports compose: "
                + resolution.getExplanation());

        String resolved = resolution.getResolvedCode();
        assertTrue(resolved.contains("import java.time.Instant;"),
            "ours' addition must survive: " + resolved);
        assertTrue(resolved.contains("import java.util.List;"),
            "the imports neither side touched must survive: " + resolved);
        assertTrue(resolved.contains("import java.util.Map;"), resolved);
        assertTrue(resolved.contains("import java.util.Set;"),
            "the default policy keeps it: theirs only dropped an unused import, and "
                + "dropping it could break a use on our branch: " + resolved);
        assertNotEquals(fixture.base(), resolved, "something must actually change");
    }
    //#endregion

    //#region default-policy-keeps-the-import
    @Test
    @DisplayName("the default policy keeps an import one side removed")
    void defaultPolicyKeepsTheImport() {
        assertEquals(ImportClashPolicy.ADDITION_WINS, ImportClashPolicy.defaultPolicy());

        ThreeWayFixture disputed = ThreeWayFixture.load(DISPUTED_CASE);
        ConflictResolution resolution =
            resolver.resolve(disputed.conflict(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind(),
            "the default makes this mechanical, not a judgement call for a human");
        assertTrue(resolution.getResolvedCode().contains("import java.util.Set;"),
            "an unused import is harmless; a dropped one breaks uses of it");
        assertTrue(resolution.getExplanation().contains("ADDITION_WINS"),
            "the explanation must name the policy that decided it: "
                + resolution.getExplanation());
    }
    //#endregion

    //#region removal-policy-honours-the-removal
    @Test
    @DisplayName("configuring REMOVAL_WINS honours the removal and escalates it")
    void removalPolicyHonoursTheRemoval() {
        ImportConflictResolver removalWins =
            new ImportConflictResolver(ImportClashPolicy.REMOVAL_WINS);

        ThreeWayFixture disputed = ThreeWayFixture.load(DISPUTED_CASE);
        ConflictResolution resolution =
            removalWins.resolve(disputed.conflict(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind(),
            "dropping an import can break a use of it, so it is not applied silently: "
                + resolution.getExplanation());
        assertFalse(resolution.getResolvedCode().contains("import java.util.Set;"),
            "the removal is honoured: " + resolution.getResolvedCode());
        assertTrue(resolution.getResolvedCode().contains("import java.util.List;"),
            "imports nobody disputed are untouched: " + resolution.getResolvedCode());
        assertTrue(resolution.getExplanation().contains("REMOVAL_WINS"),
            "the explanation must name the policy: " + resolution.getExplanation());
    }
    //#endregion

    @Test
    @DisplayName("both policies produce a structurally valid import block")
    void bothPoliciesProduceValidImports() {
        ThreeWayFixture disputed = ThreeWayFixture.load(DISPUTED_CASE);
        for (ImportClashPolicy policy : ImportClashPolicy.values()) {
            String resolved = new ImportConflictResolver(policy)
                .resolve(disputed.conflict(ConflictType.IMPORT_ADD))
                .getResolvedCode();

            assertTrue(ResolutionVerifier.wasBalanced(resolved), policy + " unbalanced");
            for (String line : resolved.split("\n")) {
                if (!line.isBlank()) {
                    assertTrue(line.startsWith("import ") && line.endsWith(";"),
                        policy + " emitted a partial declaration: " + line);
                }
            }
        }
    }

    //#region policy-is-the-only-difference
    @Test
    @DisplayName("the policy is the only difference between the two outcomes")
    void policyIsTheOnlyDifference() {
        Conflict conflict = ThreeWayFixture.load(DISPUTED_CASE)
            .conflict(ConflictType.IMPORT_ADD);

        String kept = new ImportConflictResolver(ImportClashPolicy.ADDITION_WINS)
            .resolve(conflict).getResolvedCode();
        String dropped = new ImportConflictResolver(ImportClashPolicy.REMOVAL_WINS)
            .resolve(conflict).getResolvedCode();

        String keptWithout = kept.replace("import java.util.Set;\n", "");
        assertEquals(keptWithout, dropped,
            "the policies must differ only in whether the clashing import is present");
    }
    //#endregion

    //#region merged-imports-remain-valid
    @Test
    @DisplayName("the merged import block is still syntactically valid")
    void mergedImportsRemainValid() {
        String resolved = resolver.resolve(fixture.conflict(ConflictType.IMPORT_ADD))
            .getResolvedCode();

        assertTrue(ResolutionVerifier.wasBalanced(resolved), "the merged block must be balanced");
        for (String line : resolved.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(line.startsWith("import ") && line.endsWith(";"),
                    "every emitted line must be a complete import declaration, was: " + line);
            }
        }
    }
    //#endregion

    @Test
    @DisplayName("both branches making the same change is agreement, applied once")
    void sameChangeOnBothSidesIsAgreement() {
        // ours and theirs both add the same import.
        ImportChange same = ImportChange.between(
            Set.of(ref("java.util.List")), Set.of(ref("java.util.List"), ref("java.time.Instant")));

        assertTrue(same.agreesWith(same), "identical changes agree");
        List<ImportChange.ImportRef> merged = same.mergeWith(same).orElseThrow();
        assertEquals(2, merged.size(), "an agreed addition must not be duplicated: " + merged);
    }

    @Test
    @DisplayName("one side re-adding what the other removed is a clash for the policy to settle")
    void readditionAfterRemovalIsAClash() {
        ImportChange adds = ImportChange.between(Set.of(), Set.of(ref("java.util.Set")));
        ImportChange removes = ImportChange.between(Set.of(ref("java.util.Set")), Set.of());

        assertTrue(adds.clashesWith(removes),
            "the resolver must recognise this as the case needing a policy");
        assertTrue(adds.mergeWith(removes).isEmpty(),
            "and must not compose it silently - the policy decides, not the merge");
    }

    @Test
    @DisplayName("neither side touching imports is not this resolver's conflict")
    void unchangedImportsDecline() {
        ImportChange none = ImportChange.between(Set.of(ref("java.util.List")),
            Set.of(ref("java.util.List")));

        assertTrue(none.isUnchanged());
        assertTrue(none.added().isEmpty());
        assertTrue(none.removed().isEmpty());
    }

    private static ImportChange.ImportRef ref(String name) {
        return ImportChange.ImportRef.parse("import " + name + ";").orElseThrow();
    }

    private static List<String> names(Set<ImportChange.ImportRef> refs) {
        return refs.stream().map(ImportChange.ImportRef::name).sorted().collect(Collectors.toList());
    }
}
