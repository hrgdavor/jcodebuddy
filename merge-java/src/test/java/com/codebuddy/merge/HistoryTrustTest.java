// {@link com.codebuddy.merge.HistoryTrustTest} Tests for schema versioning, validation, pruning and signature stability.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision history is checked in and replayed automatically, so it must be
 * trustworthy over time: versioned so a format change cannot be misread,
 * validated so a corrupt entry cannot be applied, pruned so it stays bounded, and
 * based on a signature that provably does not merge distinct disagreements.
 */
class HistoryTrustTest {

    private static final String BRANCH = "feature";

    @TempDir
    Path tempDir;

    private BranchConflictStore store() {
        return new BranchConflictStore(BRANCH, tempDir.resolve(BRANCH));
    }

    private Conflict conflict() {
        return ConflictFixtures.sample(ConflictType.IMPORT_ADD);
    }

    private ConflictResolution decisionFor(Conflict conflict) {
        return ConflictResolution.builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .resolvedCode(conflict.getBranch1Code())
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .sticky(true)
            .branchName(BRANCH)
            .build();
    }

    // ------------------------------------------------------------ versioning

    @Test
    @DisplayName("writes a schema version into every decision")
    void writesSchemaVersion() {
        Conflict conflict = conflict();
        String json = BranchConflictStore.DecisionFile.write(
            ConflictSignature.of(conflict), decisionFor(conflict));

        assertTrue(json.contains("\"schemaVersion\": " + BranchConflictStore.SCHEMA_VERSION),
            "a decision file must declare its format version: " + json);
    }

    @Test
    @DisplayName("reads a decision written by this version")
    void readsCurrentVersion() {
        Conflict conflict = conflict();
        var entry = BranchConflictStore.DecisionFile.read(
            BranchConflictStore.DecisionFile.write(
                ConflictSignature.of(conflict), decisionFor(conflict)));

        assertTrue(entry.isPresent());
    }

    @Test
    @DisplayName("refuses a decision from a newer schema rather than misreading it")
    void refusesNewerSchema() {
        Conflict conflict = conflict();
        String json = BranchConflictStore.DecisionFile
            .write(ConflictSignature.of(conflict), decisionFor(conflict))
            .replace("\"schemaVersion\": " + BranchConflictStore.SCHEMA_VERSION,
                "\"schemaVersion\": " + (BranchConflictStore.SCHEMA_VERSION + 1));

        assertTrue(BranchConflictStore.DecisionFile.read(json).isEmpty(),
            "a future format must be ignored, never guessed at");
    }

    @Test
    @DisplayName("refuses an unparsable schema version")
    void refusesUnparsableVersion() {
        Conflict conflict = conflict();
        String json = BranchConflictStore.DecisionFile
            .write(ConflictSignature.of(conflict), decisionFor(conflict))
            .replace("\"schemaVersion\": " + BranchConflictStore.SCHEMA_VERSION,
                "\"schemaVersion\": \"not-a-number\"");

        assertTrue(BranchConflictStore.DecisionFile.read(json).isEmpty());
    }

    @Test
    @DisplayName("treats a file with no version as the current version")
    void treatsMissingVersionAsCurrent() {
        Conflict conflict = conflict();
        String json = BranchConflictStore.DecisionFile
            .write(ConflictSignature.of(conflict), decisionFor(conflict))
            .replace("  \"schemaVersion\": " + BranchConflictStore.SCHEMA_VERSION + ",\n", "");

        assertTrue(BranchConflictStore.DecisionFile.read(json).isPresent(),
            "files written before versioning must still be readable");
    }

    // ---------------------------------------------------------- diagnostics

    @Test
    @DisplayName("skips a corrupt decision and records why, without failing the merge")
    void skipsCorruptDecisionWithDiagnostic() throws IOException {
        Path decisions = tempDir.resolve(BRANCH).resolve(BranchConflictStore.DECISIONS_DIR);
        Files.createDirectories(decisions);
        Files.writeString(decisions.resolve("broken.json"), "{ this is not json",
            StandardCharsets.UTF_8);

        Conflict conflict = conflict();
        BranchConflictStore store = store();
        store.record(conflict, decisionFor(conflict));

        // Reopen: the good entry loads, the corrupt one is reported and ignored.
        BranchConflictStore reopened = new BranchConflictStore(BRANCH, tempDir.resolve(BRANCH));

        assertEquals(1, reopened.size(), "the valid decision must still load");
        assertTrue(reopened.findDecision(conflict).isPresent());
        assertFalse(reopened.getLoadDiagnostics().isEmpty(),
            "a skipped entry must be reported, not silently dropped");
        assertTrue(reopened.getLoadDiagnostics().get(0).contains("broken.json"),
            "the diagnostic must name the file: " + reopened.getLoadDiagnostics());
    }

    @Test
    @DisplayName("records a diagnostic for an entry naming an unknown conflict type")
    void diagnosesUnknownConflictType() throws IOException {
        Path decisions = tempDir.resolve(BRANCH).resolve(BranchConflictStore.DECISIONS_DIR);
        Files.createDirectories(decisions);
        Files.writeString(decisions.resolve("unknown.json"),
            "{\n \"conflictType\": \"NOT_A_REAL_TYPE\",\n \"filePath\": \"A.java\",\n"
                + " \"contentHash\": \"abc\",\n \"resolutionStrategy\": \"KEEP_BOTH\"\n}",
            StandardCharsets.UTF_8);

        BranchConflictStore store = new BranchConflictStore(BRANCH, tempDir.resolve(BRANCH));

        assertEquals(0, store.size());
        assertEquals(1, store.getLoadDiagnostics().size());
    }

    @Test
    @DisplayName("a clean load reports no diagnostics")
    void cleanLoadReportsNothing() {
        Conflict conflict = conflict();
        store().record(conflict, decisionFor(conflict));

        BranchConflictStore reopened = new BranchConflictStore(BRANCH, tempDir.resolve(BRANCH));

        assertTrue(reopened.getLoadDiagnostics().isEmpty(),
            "was " + reopened.getLoadDiagnostics());
    }

    // -------------------------------------------------------------- pruning

    @Test
    @DisplayName("prunes decisions older than a cutoff")
    void prunesOlderDecisions() {
        BranchConflictStore store = store();
        Conflict old = conflict();
        Conflict recent = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);

        store.record(old, ConflictResolution.copyOf(decisionFor(old))
            .resolvedAt(Instant.now().minus(Duration.ofDays(400))).build());
        store.record(recent, ConflictResolution.copyOf(decisionFor(recent))
            .resolvedAt(Instant.now()).build());

        int removed = store.prune(Instant.now().minus(Duration.ofDays(90)));

        assertEquals(1, removed, "only the stale decision should go");
        assertTrue(store.findDecision(old).isEmpty());
        assertTrue(store.findDecision(recent).isPresent(),
            "a recent decision must survive pruning");
    }

    @Test
    @DisplayName("prunes by maximum age and removes the files too")
    void prunesByAgeAndDeletesFiles() throws IOException {
        BranchConflictStore store = store();
        Conflict old = conflict();
        store.record(old, ConflictResolution.copyOf(decisionFor(old))
            .resolvedAt(Instant.now().minus(Duration.ofDays(400))).build());

        Path decisions = store.getDecisionsDir();
        assertEquals(1, countJson(decisions), "the decision should be on disk first");

        int removed = store.pruneStale(Duration.ofDays(30));

        assertEquals(1, removed);
        assertEquals(0, countJson(decisions), "pruning must delete the file as well");
    }

    @Test
    @DisplayName("pruning keeps everything when nothing is stale")
    void pruningKeepsFreshDecisions() {
        BranchConflictStore store = store();
        Conflict conflict = conflict();
        store.record(conflict, decisionFor(conflict));

        assertEquals(0, store.pruneStale(Duration.ofDays(30)));
        assertTrue(store.findDecision(conflict).isPresent());
    }

    @Test
    @DisplayName("reports the oldest decision's age")
    void reportsOldestDecision() {
        BranchConflictStore store = store();
        Instant longAgo = Instant.now().minus(Duration.ofDays(200));
        Conflict conflict = conflict();
        store.record(conflict, ConflictResolution.copyOf(decisionFor(conflict))
            .resolvedAt(longAgo).build());

        assertEquals(longAgo, store.oldestDecisionAt().orElseThrow());
    }

    @Test
    @DisplayName("an empty history has no oldest decision")
    void emptyHistoryHasNoOldest() {
        assertTrue(store().oldestDecisionAt().isEmpty());
    }

    private static long countJson(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".json")).count();
        }
    }

    // -------------------------------------------------- signature stability

    /**
     * The signature decides whether a recorded decision is replayed, so a
     * collision between two genuinely different disagreements would apply the
     * wrong resolution silently. Exhaustive proof is not possible, so this
     * generates many distinct hunks and asserts that no two share a signature.
     */
    @Test
    @DisplayName("distinct disagreements never share a signature")
    void distinctDisagreementsHaveDistinctSignatures() {
        Random random = new Random(20240921L);
        String[] shapes = {
            "import a.B%d;",
            "int value%d = %d;",
            "void method%d(String arg%d) { }",
            "// comment %d",
            "static final int CONST_%d = %d;",
            "long count%d = %d;"
        };

        java.util.Map<String, String> signatureToContent = new java.util.HashMap<>();
        int generated = 0;

        for (String shape : shapes) {
            for (int i = 0; i < 200; i++) {
                int a = random.nextInt(1000);
                int b = random.nextInt(1000);
                String branch1 = shape.formatted(a, b);
                String branch2 = shape.formatted(b, a);
                if (branch1.equals(branch2)) {
                    continue;
                }
                Conflict conflict = new Conflict(ConflictType.IMPORT_ADD, "A.java",
                    "generated", "base", branch1, branch2);

                String signature = ConflictSignature.of(conflict).contentHash();
                String content = branch1 + "\u0000" + branch2;
                String previous = signatureToContent.putIfAbsent(signature, content);
                if (previous != null) {
                    assertEquals(previous, content,
                        "two different disagreements produced the same signature: "
                            + previous + " vs " + content);
                }
                generated++;
            }
        }

        assertTrue(generated > 500, "the generator must produce a meaningful sample: " + generated);
    }

    @Test
    @DisplayName("reformatting never changes a signature")
    void reformattingNeverChangesSignature() {
        // Only whitespace and blank lines vary here. Joining two statements onto
        // one line is a real change, not reformatting, and is covered by the
        // semantic test below.
        List<String> equivalent = List.of(
            "int count = 0;\nreturn count;",
            "  int count = 0;\n  return count;  ",
            "int count=0;\nreturn count;",
            "int   count   =   0;\n\nreturn count;",
            "\n\n int count = 0; \n return count; \n\n",
            "\tint count = 0;\t\n\treturn count;\t");

        String baseline = ConflictSignature.normalise(equivalent.get(0));
        for (String variant : equivalent) {
            assertEquals(baseline, ConflictSignature.normalise(variant),
                "reformatting must not change the signature: [" + variant + "]");
        }
    }

    @Test
    @DisplayName("semantic changes always change a signature")
    void semanticChangesChangeSignature() {
        String baseline = ConflictSignature.normalise("int count = 0;");

        for (String different : List.of(
                "int count = 1;",
                "long count = 0;",
                "int other = 0;",
                "int count2 = 0;",
                "int count = 0; return count;")) {
            assertNotEquals(baseline, ConflictSignature.normalise(different),
                "a real change must change the signature: [" + different + "]");
        }
    }
}
