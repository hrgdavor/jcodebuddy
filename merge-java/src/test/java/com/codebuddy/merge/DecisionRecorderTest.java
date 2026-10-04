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
}
