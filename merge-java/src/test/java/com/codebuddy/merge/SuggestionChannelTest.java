// {@link com.codebuddy.merge.SuggestionChannelTest} A suggestion is offered, never applied, and never hidden.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import com.codebuddy.merge.ConflictResolution.ResolutionKind;
import com.codebuddy.merge.MergeFileTool.Outcome;
import com.codebuddy.merge.MergeFileTool.Result;
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
 * The suggestion channel: an answer the tool offers and does not apply (unified plan step 4.14).
 *
 * <h2>The three boundaries, each asserted directly</h2>
 *
 * <ol>
 *   <li><b>A suggestion is never applied without an explicit acceptance.</b> The markers stay and the outcome
 *       says so — {@link Outcome#LEFT_SUGGESTION}, not a flavour of "applied".</li>
 *   <li><b>A failed verification labels a suggestion, it does not suppress it.</b> Suppressing would hide
 *       information a reviewer may want; promoting would be the error the verifier gate exists to prevent.</li>
 *   <li><b>Nothing in the channel can write the resolution's code or kind.</b> The guarantee is structural —
 *       a suggestion lives in its own field and there is no method that reaches those — so the test asserts the
 *       observable consequence: a resolution whose only content is a suggestion carries <em>no</em> code and is
 *       not an automatic one.</li>
 * </ol>
 *
 * <h2>Why the channel is a kind rather than "a REVIEW that carries code"</h2>
 *
 * <p>Because the two need different rules and sharing would turn every rule into a subtype check. The one that
 * bites first is the bulk action: "apply all resolved" applies {@code AUTO} resolutions, and a reviewer
 * accepting a suggestion is making a judgement about code they have read. {@code REVIEW} means the tool is
 * prepared to apply this and wants a nod; {@link ResolutionKind#SUGGESTION} means it is yours to accept or
 * refuse, and only one of the two survives a bulk accept.
 */
class SuggestionChannelTest {

    @TempDir
    Path tempDir;

    /**
     * An import block, which detection explains completely — so the offered suggestion is the block's only
     * claim, and the test is about the channel rather than about how several claims interact.
     */
    private static final String CONFLICT_FILE = """
            package com.example.demo;

            import java.util.List;
            <<<<<<< ours
            import java.math.BigDecimal;
            =======
            import java.time.Instant;
            >>>>>>> theirs

            public class OrderService {
            }
            """;

    /** A resolver that offers an answer instead of deciding one. */
    private static final class SuggestingResolver extends AbstractConflictResolver {

        private final String code;
        private final ConflictResolution.Verification verdict;

        SuggestingResolver(String code, ConflictResolution.Verification verdict) {
            this.code = code;
            this.verdict = verdict;
        }

        @Override
        public ConflictType supportedType() {
            return ConflictType.IMPORT_ADD;
        }

        @Override
        protected ConflictResolution doResolve(Conflict conflict) {
            Suggestion suggestion = Suggestion.of(code,
                    "The two statements do not interact, so both can run.",
                    "ImportChange: the import block", AnalysisLevel.TEXT_FILE,
                    Suggestion.Confidence.PLAUSIBLE)
                .withVerification(verdict, verdict == ConflictResolution.Verification.PASSED
                    ? ""
                    : "structural check rejected the proposed text");
            return ConflictResolution.builder()
                .filePath(conflict.getFilePath())
                .type(conflict.getType())
                .baseCode(conflict.getBaseCode())
                .branch1Code(conflict.getBranch1Code())
                .branch2Code(conflict.getBranch2Code())
                .region(conflict.getRegion())
                .kind(ResolutionKind.SUGGESTION)
                .resolutionStrategy(ConflictResolution.ResolutionStrategy.MANUAL)
                .explanation("A suggestion is offered for this block.")
                .suggestion(suggestion)
                .build();
        }

        @Override
        protected List<FixPath> describeOptions(Conflict conflict) {
            return List.of();
        }
    }

    private MergeFileTool.Builder toolFor(Path file, ConflictResolver resolver) {
        return MergeFileTool.forFile(file)
            .resolver(new MergeConflictResolver.Builder()
                .setBranchName("suggestion-test")
                .setInMemoryOnly(true)
                .setResolvers(List.of(resolver, new StructuralChangeConflictResolver()))
                .build())
            .fixtureRoot(tempDir.resolve("fixture-root"))
            .inMemoryOnly(true);
    }

    @Test
    @DisplayName("a suggestion is offered and never applied: the markers stay")
    void aSuggestionIsNeverApplied() throws IOException {
        Path file = write("OrderService.java", CONFLICT_FILE);

        Result result = toolFor(file, new SuggestingResolver("import java.math.BigDecimal;\n",
            ConflictResolution.Verification.PASSED)).applyFixes(true).run();

        MergeFileTool.BlockOutcome outcome = result.outcomes().get(0);
        assertEquals(Outcome.LEFT_SUGGESTION, outcome.outcome(), outcome.explanation());
        assertEquals(1, result.exitCode(), "an offered answer is not a resolved file");
        assertTrue(read(file).contains("<<<<<<<"), "the markers stay until a person accepts: " + read(file));
        assertTrue(read(file).contains("import java.time.Instant;"),
            "the file still holds both sides of the conflict, exactly as it was: " + read(file));
    }

    @Test
    @DisplayName("a suggestion that fails verification is still there, marked as failed")
    void aFailedSuggestionIsLabelledNotHidden() {
        // The verifier is the one thing upstream does not have, so the channel must use it — and use it to
        // LABEL: suppressing a failed suggestion would hide information a reviewer may want, and promoting it
        // would be the error the gate exists to prevent.
        MergeConflictResolver orchestrator = new MergeConflictResolver.Builder()
            .setBranchName("suggestion-test")
            .setInMemoryOnly(true)
            .setResolvers(List.of(new SuggestingResolver("import java.math.BigDecimal;\n",
                ConflictResolution.Verification.NOT_RUN)))
            .setVerifier((conflict, resolution) ->
                ResolutionVerifier.Result.failed("dangling parenthesis"))
            .build();

        ConflictResolution offered = orchestrator.resolve(conflictFor());

        assertEquals(ResolutionKind.SUGGESTION, offered.getKind(),
            "a failed verdict must not turn the suggestion into something else");
        assertNotNull(offered.getSuggestion());
        assertTrue(offered.getSuggestion().hasFailedVerification(),
            "the verdict is on the suggestion: " + offered.getSuggestion());
        assertTrue(offered.getSuggestion().code().contains("import java.math.BigDecimal;"),
            "and the proposed code is still there for a person to read and repair: "
                + offered.getSuggestion().code());
    }

    @Test
    @DisplayName("nothing in the channel can write the resolution's code or kind")
    void theChannelCannotReachTheResolution() {
        Suggestion suggestion = Suggestion.of("import java.math.BigDecimal;\n", "why", "provenance",
            AnalysisLevel.TEXT_LOCAL, Suggestion.Confidence.PLAUSIBLE);

        ConflictResolution resolution = ConflictResolution.builder()
            .type(ConflictType.IMPORT_ADD)
            .kind(ResolutionKind.SUGGESTION)
            .suggestion(suggestion)
            .build();

        assertTrue(resolution.getResolvedCode().isEmpty(),
            "the channel contributes no code to the resolution: " + resolution.getResolvedCode());
        assertEquals(ResolutionKind.SUGGESTION, resolution.getKind());
        assertEquals(suggestion, resolution.getSuggestion());
        assertFalse(resolution.isReplayable(),
            "a suggestion is not a decision, so it is never recorded and never replayed");
        assertFalse(resolution.isVerifiedAuto(), "and it is never an automatic answer");
    }

    private static Conflict conflictFor() {
        return new Conflict(ConflictType.IMPORT_ADD, "OrderService.java", "sample",
            "import java.util.List;\n", "import java.math.BigDecimal;\n", "import java.time.Instant;\n");
    }

    private Path write(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
