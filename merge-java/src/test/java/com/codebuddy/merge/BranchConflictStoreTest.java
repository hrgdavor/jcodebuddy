// {@link com.codebuddy.merge.BranchConflictStoreTest} Tests for per-branch decision history.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the memory that makes repeated base-branch updates cheap: a decision
 * is recorded on disk, found again for the same conflict, and never applied to a
 * conflict it was not made for.
 */
class BranchConflictStoreTest {

    private static final String BRANCH = "feature-payments";

    @TempDir
    Path tempDir;

    private BranchConflictStore store() {
        return new BranchConflictStore(BRANCH, tempDir.resolve(BRANCH));
    }

    /**
     * An import conflict resolves to a replayable decision.
     */
    private Conflict conflict() {
        return ConflictFixtures.sample(ConflictType.IMPORT_ADD);
    }

    private ConflictResolution replayableDecisionFor(Conflict conflict) {
        return new ImportConflictResolver().resolve(conflict);
    }

    @Test
    @DisplayName("records a replayable decision and finds it again for the same conflict")
    void recordsAndFindsDecision() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();

        store.record(conflict, replayableDecisionFor(conflict));

        assertTrue(store.findDecision(conflict).isPresent(),
            "the decision must be replayable for the same conflict");
        assertEquals(1, store.size());
    }

    @Test
    @DisplayName("writes the decision under the branch directory")
    void writesToBranchDirectory() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();
        store.record(conflict, replayableDecisionFor(conflict));

        assertTrue(Files.isDirectory(store.getDecisionsDir()),
            "the decisions directory must exist: " + store.getDecisionsDir());
        try (var files = Files.list(store.getDecisionsDir())) {
            assertEquals(1, files.count(), "exactly one decision file is expected");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(store.getDecisionsDir().toString().contains(BRANCH),
            "history must be scoped to the branch: " + store.getDecisionsDir());
    }

    @Test
    @DisplayName("survives a restart by reloading decisions from disk")
    void reloadsDecisionsFromDisk() {
        Conflict conflict = conflict();
        BranchConflictStore first = store();
        first.record(conflict, replayableDecisionFor(conflict));

        BranchConflictStore reopened = new BranchConflictStore(BRANCH, tempDir.resolve(BRANCH));

        assertTrue(reopened.findDecision(conflict).isPresent(),
            "a recorded decision must outlive the process that made it");
    }

    @Test
    @DisplayName("never replays a decision for a different conflict")
    void doesNotLeakDecisionsAcrossConflicts() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();
        store.record(conflict, replayableDecisionFor(conflict));

        Conflict different = new Conflict(ConflictType.IMPORT_ADD, conflict.getFilePath(),
            "a different disagreement", "base",
            "import com.example.Other;", "import com.example.Another;");

        assertTrue(store.findDecision(different).isEmpty(),
            "a decision made for one disagreement must not be applied to another");
    }

    @Test
    @DisplayName("does not replay decisions across files")
    void doesNotLeakDecisionsAcrossFiles() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();
        store.record(conflict, replayableDecisionFor(conflict));

        Conflict elsewhere = new Conflict(ConflictType.IMPORT_ADD, "src/Other.java",
            conflict.getDescription(), conflict.getBaseCode(),
            conflict.getBranch1Code(), conflict.getBranch2Code());

        assertTrue(store.findDecision(elsewhere).isEmpty(),
            "history is per file, not global");
    }

    @Test
    @DisplayName("refuses to store a non-replayable decision")
    void doesNotStoreNonReplayableDecision() {
        BranchConflictStore store = store();
        Conflict conflict = ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE);

        store.record(conflict, new StructuralChangeConflictResolver().resolve(conflict));

        assertEquals(0, store.size(),
            "a one-off manual decision is not a policy and must not be replayed");
    }

    @Test
    @DisplayName("records a sticky rename decision")
    void recordsStickyRenameDecision() {
        BranchConflictStore store = store();
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);

        store.record(conflict, new RenameConflictResolver().resolve(conflict));

        assertTrue(store.findDecision(conflict).isPresent(),
            "a rename choice is worth remembering");
    }

    @Test
    @DisplayName("lists the decisions recorded for a file")
    void listsDecisionsForFile() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();
        store.record(conflict, replayableDecisionFor(conflict));

        List<ConflictResolution> forFile = store.decisionsForFile(conflict.getFilePath());

        assertEquals(1, forFile.size());
        assertEquals(conflict.getFilePath(), forFile.get(0).getFilePath());
        assertTrue(store.decisionsForFile("src/Nowhere.java").isEmpty());
    }

    @Test
    @DisplayName("forgets a single decision")
    void forgetsDecision() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();
        store.record(conflict, replayableDecisionFor(conflict));

        boolean forgotten = store.forget(ConflictSignature.of(conflict));

        assertTrue(forgotten, "forgetting a known decision should report success");
        assertTrue(store.findDecision(conflict).isEmpty());
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("forgetting an unknown decision is harmless")
    void forgettingUnknownDecisionIsHarmless() {
        assertFalse(store().forget(ConflictSignature.of(conflict())));
    }

    @Test
    @DisplayName("clears all recorded decisions")
    void clearsAllDecisions() {
        BranchConflictStore store = store();
        store.record(conflict(), replayableDecisionFor(conflict()));
        store.record(ConflictFixtures.sample(ConflictType.VARIABLE_RENAME),
            new RenameConflictResolver().resolve(ConflictFixtures.sample(ConflictType.VARIABLE_RENAME)));

        store.clear();

        assertEquals(0, store.size());
        assertTrue(store.findDecision(conflict()).isEmpty());
    }

    @Test
    @DisplayName("round-trips the resolution through the decision file format")
    void roundTripsDecisionFile() {
        ConflictSignature signature = ConflictSignature.of(conflict());
        ConflictResolution original = replayableDecisionFor(conflict());

        String json = BranchConflictStore.DecisionFile.write(signature, original);
        assertTrue(json.contains("\"conflictType\""), "the file must record the type");

        var restored = BranchConflictStore.DecisionFile.read(json);

        assertTrue(restored.isPresent(), "a written decision must be readable");
        assertEquals(signature, restored.get().signature(),
            "the signature must survive the round trip");
        assertEquals(original.getResolutionStrategy(),
            restored.get().resolution().getResolutionStrategy());
        assertEquals(original.getResolvedCode(), restored.get().resolution().getResolvedCode(),
            "the resolved code must survive escaping");
        assertEquals(original.getKind(), restored.get().resolution().getKind());
    }

    @Test
    @DisplayName("escapes quotes and newlines in recorded code")
    void escapesSpecialCharacters() {
        ConflictSignature signature = new ConflictSignature(ConflictType.COMMENT_ADD, "A.java", "abc");
        ConflictResolution resolution = ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.COMMENT_ADD)
            .resolvedCode("// a \"quoted\" comment\nline two\twith tab")
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .kind(ConflictResolution.ResolutionKind.AUTO)
            .sticky(true)
            .branchName(BRANCH)
            .build();

        var restored = BranchConflictStore.DecisionFile.read(
            BranchConflictStore.DecisionFile.write(signature, resolution));

        assertEquals(resolution.getResolvedCode(),
            restored.orElseThrow().resolution().getResolvedCode());
    }

    @Test
    @DisplayName("ignores a malformed decision file instead of failing")
    void ignoresMalformedDecisionFile() {
        assertTrue(BranchConflictStore.DecisionFile.read("not json at all").isEmpty());
        assertTrue(BranchConflictStore.DecisionFile.read("{}").isEmpty());
        assertTrue(BranchConflictStore.DecisionFile.read(
            "{\n \"conflictType\": \"NOT_A_TYPE\"\n}").isEmpty());
    }

    @Test
    @DisplayName("rejects a stored decision that is not replayable")
    void rejectsNonReplayableStoredDecision() {
        ConflictSignature signature = new ConflictSignature(ConflictType.STRUCTURAL_CHANGE, "A.java", "abc");
        ConflictResolution resolution = ConflictResolution.builder()
            .filePath("A.java")
            .type(ConflictType.STRUCTURAL_CHANGE)
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.MANUAL)
            .sticky(false)
            .build();

        assertTrue(BranchConflictStore.DecisionFile.read(
            BranchConflictStore.DecisionFile.write(signature, resolution)).isEmpty(),
            "a non-replayable decision must not come back as policy");
    }

    @Test
    @DisplayName("exposes its identity and configured locations")
    void exposesIdentity() {
        BranchConflictStore store = store();

        assertEquals(BRANCH, store.getBranchName());
        assertNotNull(store.getHistoryRoot());
        assertNotNull(store.getDecisionsDir());
        assertEquals(0, store.size());
        assertTrue(store.allDecisions().isEmpty());
    }
}
