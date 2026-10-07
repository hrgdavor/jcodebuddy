// {@link com.codebuddy.merge.RejectionMemoryTest} A refused answer is not offered again (plan step 4.17).
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejection memory: refusing a suggestion must mean something on the next run (unified plan step 4.17).
 *
 * <h2>Why this is the rule most likely to be skipped, and what skipping costs</h2>
 *
 * <p>If a reviewer refuses an answer and the next merge offers it again, the channel has made the tool worse than
 * not having one — it is the difference between helpful and nagging. So a refusal is recorded against the
 * conflict's <b>signature</b> and the answer's <b>provenance</b>, and suppression is per
 * <em>(signature, provenance)</em>: refusing "the syntactic body merge produced this" must not refuse a future
 * structural answer for the same conflict, because that is an answer the reviewer has not seen.
 *
 * <h2>The two halves, and the one that is easy to get wrong</h2>
 *
 * <p>Recording it is the visible half. The invisible half is that the recorder must file the refusal under the
 * <b>same key the resolver computes</b> — the page's refusal entry carries the signature it showed and no sides,
 * so rebuilding the signature from the sides would file it under a name that never matches, and a refusal that
 * cannot be found again looks exactly like a refusal that worked. {@link #theRefusalIsFiledUnderTheKeyTheResolverComputes()}
 * is that assertion.
 */
class RejectionMemoryTest {

    @TempDir
    Path tempDir;

    /** The conflict the fixtures use for a resolver whose review path computes an answer. */
    private Conflict conflict() {
        return ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE);
    }

    private MergeConflictResolver resolverFor(Path historyRoot) {
        return new MergeConflictResolver.Builder()
            .setBranchName("feature")
            .setHistoryPath(historyRoot)
            .setTypeContext(TestTypeContexts.jdk())
            .build();
    }

    /** The decisions file the page exports for one refusal, without the sides a decision would carry. */
    private Path refusalFile(String signature, String provenance) throws IOException {
        String json = """
            {"schemaVersion": 1, "branchName": "feature", "decisions": [],
             "rejected": [{"signature": "%s", "type": "METHOD_BODY_CHANGE",
               "filePath": "%s", "provenance": "%s"}]}
            """.formatted(signature, conflict().getFilePath(), provenance);
        Path file = tempDir.resolve("decisions-feature.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("a refused answer is not offered again, and the channel says so")
    void aRefusedAnswerIsNotOfferedAgain() throws IOException {
        Path history = tempDir.resolve("history");
        String provenance = new MethodBodyChangeConflictResolver().name();
        String signature = ConflictSignature.of(conflict()).toFileName();

        // First run, before any refusal: the answer is offered.
        ConflictResolution before = resolverFor(history).resolve(conflict());
        assertEquals(ConflictResolution.ResolutionKind.SUGGESTION, before.getKind());
        assertNotNull(before.getSuggestion());

        DecisionRecorder.Result recorded =
            DecisionRecorder.record(refusalFile(signature, provenance), history, "feature");
        assertEquals(1, recorded.rejected(), recorded.problems().toString());
        assertTrue(recorded.problems().isEmpty(), recorded.problems().toString());

        // Second run: not offered again, and the explanation says why rather than leaving a silent gap.
        ConflictResolution after = resolverFor(history).resolve(conflict());
        assertEquals(ConflictResolution.ResolutionKind.REVIEW, after.getKind(), after.getExplanation());
        assertNull(after.getSuggestion(), "a refused answer is not offered again");
        assertTrue(after.getResolvedCode().isEmpty(),
            "and its code is dropped, because keeping it would put the refused text back in the editor");
        assertTrue(after.getExplanation().contains("refused on an earlier run"), after.getExplanation());
    }

    @Test
    @DisplayName("a refusal is per provenance: another producer's answer is still offered")
    void anotherProvenanceIsStillOffered() throws IOException {
        Path history = tempDir.resolve("history");
        String signature = ConflictSignature.of(conflict()).toFileName();

        DecisionRecorder.record(refusalFile(signature, "a word-level comparison"), history, "feature");

        ConflictResolution resolution = resolverFor(history).resolve(conflict());

        assertEquals(ConflictResolution.ResolutionKind.SUGGESTION, resolution.getKind(),
            "refusing one provenance must not refuse an answer the reviewer has never seen");
        assertNotNull(resolution.getSuggestion());
        assertEquals("MethodBodyChange", resolution.getSuggestion().provenance());
    }

    @Test
    @DisplayName("the refusal is filed under the key the resolver computes")
    void theRefusalIsFiledUnderTheKeyTheResolverComputes() throws IOException {
        Path history = tempDir.resolve("history");
        String signature = ConflictSignature.of(conflict()).toFileName();

        DecisionRecorder.record(refusalFile(signature, "MethodBodyChange"), history, "feature");

        BranchConflictStore store = new BranchConflictStore("feature", history);
        assertTrue(store.isRejected(conflict(), "MethodBodyChange"),
            "a refusal that cannot be found again looks exactly like a refusal that worked");
        assertEquals(1, store.rejectedConflictCount());
        assertFalse(store.isRejected(conflict(), "something else"));
    }

    @Test
    @DisplayName("a refusal survives being written and read back")
    void aRefusalSurvivesARoundTrip() {
        Path history = tempDir.resolve("history");
        BranchConflictStore store = new BranchConflictStore("feature", history);

        store.recordRejection(conflict(), "MethodBodyChange");

        BranchConflictStore reloaded = new BranchConflictStore("feature", history);
        assertTrue(reloaded.isRejected(conflict(), "MethodBodyChange"));
        assertTrue(Files.isRegularFile(history.resolve("decisions").resolve(BranchConflictStore.REJECTIONS_FILE)),
            "the refusals live in one sidecar beside the decisions");
    }

    @Test
    @DisplayName("a refusal with no provenance is refused, rather than suppressing every answer")
    void aRefusalWithoutProvenanceIsRejected() throws IOException {
        Path history = tempDir.resolve("history");
        String signature = ConflictSignature.of(conflict()).toFileName();

        DecisionRecorder.Result result =
            DecisionRecorder.record(refusalFile(signature, ""), history, "feature");

        assertEquals(0, result.rejected());
        assertFalse(result.problems().isEmpty(), "and the reason is reported");
        assertTrue(new BranchConflictStore("feature", history).rejectedConflictCount() == 0,
            "nothing was suppressed: an unnamed refusal would suppress the next producer's answer too");
    }
}
