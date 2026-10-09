package com.codebuddy.merge;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The action half of step 4.3: a choice a reviewer accepted in the review page must reach
 * {@link BranchConflictStore} and be <strong>replayed</strong> by the next merge run, exactly like any other sticky
 * decision.
 *
 * <p>The page's own output is simulated here by building the same payload from a real conflict's parts — the page
 * reads them out of the report ({@code signature}, {@code type}, {@code filePath}, {@code sides}) and adds what the
 * reviewer chose. The keys are the contract, and the Bun test beside the page pins the other side of it.</p>
 */
class DecisionRecorderTest {

    private static final String BRANCH = "feature";

    @TempDir
    Path tempDir;

    /** The payload the review page exports for one accepted decision. */
    private Path exportedDecision(Conflict conflict, String resolvedCode, String signatureOverride)
            throws Exception {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("signature", signatureOverride != null
            ? signatureOverride
            : ConflictSignature.of(conflict).toFileName());
        decision.put("type", conflict.getType().name());
        decision.put("filePath", conflict.getFilePath());
        decision.put("description", conflict.getDescription());
        decision.put("base", conflict.getBaseCode());
        decision.put("branch1", conflict.getBranch1Code());
        decision.put("branch2", conflict.getBranch2Code());
        decision.put("resolvedCode", resolvedCode);
        decision.put("explanation", "Reviewer chose branch 1's name");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", DecisionRecorder.SCHEMA_VERSION);
        document.put("branchName", BRANCH);
        document.put("decisions", List.of(decision));

        Path file = tempDir.resolve("decisions.json");
        Files.writeString(file, new ObjectMapper().writeValueAsString(document), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a decision exported by the page is replayed on the next merge run")
    void aDecisionFromThePageIsReplayed() throws Exception {
        Path history = tempDir.resolve(BRANCH);
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        Path decisions = exportedDecision(conflict, conflict.getBranch1Code(), null);

        DecisionRecorder.Result result = DecisionRecorder.record(decisions, history, BRANCH);

        Assertions.assertEquals(1, result.recorded(), result.describe());

        // A later update of the same branch sees the same conflict, and must not re-litigate it.
        ConflictResolution replayed = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .resolve(conflict);

        Assertions.assertEquals(ConflictResolution.ResolutionKind.DEFERRED, replayed.getKind(),
            "a recorded decision must be replayed, not re-litigated");
        Assertions.assertEquals(ConflictResolution.ResolutionStrategy.STICKY_REPLAY,
            replayed.getResolutionStrategy());
        Assertions.assertEquals(conflict.getBranch1Code(), replayed.getResolvedCode(),
            "and what replays is the code the reviewer accepted");
        Assertions.assertEquals(conflict.getFilePath(), replayed.getFilePath(),
            "re-anchored to the incoming file, as every replayed decision is");
    }

    @Test
    @DisplayName("a payload naming a key that is not this conflict's is refused, not recorded")
    void aMismatchedKeyIsRefused() throws Exception {
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        Path decisions = exportedDecision(conflict, conflict.getBranch1Code(), "variable_rename-notthiskey");

        DecisionRecorder.Result result = DecisionRecorder.record(decisions, tempDir, BRANCH);

        Assertions.assertEquals(0, result.recorded(), "nothing may be recorded under the wrong conflict");
        Assertions.assertEquals(1, result.problems().size(), result.describe());
        Assertions.assertTrue(result.problems().get(0).contains("notthiskey"), result.problems().toString());
    }

    @Test
    @DisplayName("a payload from an unknown format version is refused rather than misread")
    void anUnknownSchemaVersionIsRefused() throws Exception {
        Path decisions = tempDir.resolve("decisions.json");
        Files.writeString(decisions,
            "{\"schemaVersion\": 99, \"branchName\": \"" + BRANCH + "\", \"decisions\": []}",
            StandardCharsets.UTF_8);

        java.io.IOException refused = Assertions.assertThrows(java.io.IOException.class,
            () -> DecisionRecorder.record(decisions, tempDir, BRANCH));

        Assertions.assertTrue(refused.getMessage().contains("99"), refused.getMessage());
    }

    @Test
    @DisplayName("an empty export records nothing and says so")
    void anEmptyExportRecordsNothing() throws Exception {
        Path decisions = tempDir.resolve("decisions.json");
        Files.writeString(decisions,
            "{\"schemaVersion\": 1, \"branchName\": \"" + BRANCH + "\", \"decisions\": []}",
            StandardCharsets.UTF_8);

        DecisionRecorder.Result result = DecisionRecorder.record(decisions, tempDir, BRANCH);

        Assertions.assertEquals(0, result.recorded());
        Assertions.assertTrue(result.isEmpty());
        Assertions.assertTrue(result.problems().isEmpty(), result.problems().toString());
    }

    /** The payload with a {@code repoPath}, which is what lets the CLI be run from anywhere (step 4.13). */
    private Path exportedDecisionWithRepo(Conflict conflict, String repoPath) throws Exception {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("signature", ConflictSignature.of(conflict).toFileName());
        decision.put("type", conflict.getType().name());
        decision.put("filePath", conflict.getFilePath());
        decision.put("base", conflict.getBaseCode());
        decision.put("branch1", conflict.getBranch1Code());
        decision.put("branch2", conflict.getBranch2Code());
        decision.put("resolvedCode", conflict.getBranch1Code());

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", DecisionRecorder.SCHEMA_VERSION);
        document.put("branchName", BRANCH);
        document.put("repoPath", repoPath);
        document.put("decisions", List.of(decision));

        Path file = tempDir.resolve("decisions-with-repo.json");
        Files.writeString(file, new ObjectMapper().writeValueAsString(document), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a decisions file naming an existing repository is recorded from any working directory")
    void aRepoPathIsHonoured() throws Exception {
        // The point of the field: the same command works from somewhere that is not the repository.
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        Path decisions = exportedDecisionWithRepo(conflict, tempDir.toAbsolutePath().toString());

        DecisionRecorder.Result result = DecisionRecorder.record(decisions, tempDir.resolve(BRANCH), BRANCH);

        Assertions.assertEquals(1, result.recorded(), result.describe());
    }

    @Test
    @DisplayName("a decisions file naming a repository that is gone is refused, not applied somewhere else")
    void aMissingRepoPathIsRefused() throws Exception {
        // The failure this check exists for: without it the decisions would be recorded against whatever
        // directory the caller happened to be in, keyed on paths that name nothing — a silent wrong answer.
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        Path gone = tempDir.resolve("moved-away-repo");
        Path decisions = exportedDecisionWithRepo(conflict, gone.toAbsolutePath().toString());

        java.io.IOException refused = Assertions.assertThrows(java.io.IOException.class,
            () -> DecisionRecorder.record(decisions, tempDir.resolve(BRANCH), BRANCH));

        Assertions.assertTrue(refused.getMessage().contains("moved-away-repo"), refused.getMessage());
        Assertions.assertTrue(refused.getMessage().contains("--repo"),
            "and it names the way out: " + refused.getMessage());
    }

    @Test
    @DisplayName("an explicit --repo overrides a repoPath that no longer exists")
    void anExplicitRepoOverridesTheDocument() throws Exception {
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        Path decisions = exportedDecisionWithRepo(conflict, tempDir.resolve("moved-away-repo").toString());

        DecisionRecorder.Result result = DecisionRecorder.record(decisions, tempDir.resolve(BRANCH), BRANCH,
            tempDir.toAbsolutePath());

        Assertions.assertEquals(1, result.recorded(),
            "a moved checkout is the case the override exists for: " + result.describe());
    }

    @Test
    @DisplayName("a decisions file written before repoPath existed still records from the working directory")
    void aDocumentWithoutRepoPathIsUnchanged() throws Exception {
        // Additive field, unchanged schema: the files reviewers already have keep working exactly as they did.
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);
        Path decisions = exportedDecision(conflict, conflict.getBranch1Code(), null);

        DecisionRecorder.Result result = DecisionRecorder.record(decisions, tempDir.resolve(BRANCH), BRANCH);

        Assertions.assertEquals(1, result.recorded(), result.describe());
    }
}
