// {@link com.codebuddy.merge.MergeConflictResolverTest} Tests for the orchestrator.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the orchestration contract: every conflict gets a resolution, sticky
 * decisions are replayed before the resolver is consulted, and a human is only
 * involved when there is genuinely no safe answer.
 */
class MergeConflictResolverTest {

    private static final String BRANCH = "feature-payments";

    @TempDir
    Path tempDir;

    private MergeConflictResolver resolver() {
        return sizedBuilder().build();
    }

    /**
     * A builder with the default resolver set, an isolated history directory and a
     * type context.
     *
     * <p>The default set includes {@link OverloadAddConflictResolver}, which
     * declares that it needs a type context, so the builder refuses to be built
     * without one. Supplying it here keeps every test in this class about what it
     * is actually testing rather than about parser wiring.
     */
    private MergeConflictResolver.Builder sizedBuilder() {
        return new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(tempDir.resolve(BRANCH))
            .setTypeContext(TestTypeContexts.jdk());
    }

    // --------------------------------------------------------------- basics

    @Test
    @DisplayName("uses the built-in resolver set by default")
    void usesBuiltInResolvers() {
        MergeConflictResolver resolver = resolver();

        assertEquals(ConflictResolvers.defaultResolvers().size(), resolver.getResolvers().size(),
            "a new resolver registered in ConflictResolvers must flow through automatically");
        assertEquals(BRANCH, resolver.getBranchName());
    }

    @Test
    @DisplayName("resolves a conflict through the registered resolver")
    void resolvesThroughRegistry() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);

        ConflictResolution resolution = resolver().resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
        assertTrue(resolution.getResolvedCode().contains("import java.time.Instant;"));
    }

    @Test
    @DisplayName("resolves a list of conflicts preserving order")
    void resolvesAllInOrder() {
        List<Conflict> conflicts = List.of(
            ConflictFixtures.sample(ConflictType.IMPORT_ADD),
            ConflictFixtures.sample(ConflictType.COMMENT_ADD));

        List<ConflictResolution> resolutions = resolver().resolveAll(conflicts);

        assertEquals(2, resolutions.size());
        assertEquals(ConflictType.IMPORT_ADD, resolutions.get(0).getType());
        assertEquals(ConflictType.COMMENT_ADD, resolutions.get(1).getType());
    }

    @Test
    @DisplayName("hands a conflict to a human when no resolver is registered")
    void fallsBackWhenNoResolverRegistered() {
        MergeConflictResolver bare = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(tempDir.resolve("bare"))
            .setResolvers(List.of())
            .build();

        ConflictResolution resolution =
            bare.resolve(ConflictFixtures.sample(ConflictType.IMPORT_ADD));

        assertEquals(ConflictResolution.ResolutionKind.MANUAL, resolution.getKind());
        assertTrue(resolution.getExplanation().contains("No resolver is registered"),
            "the explanation must say why: " + resolution.getExplanation());
        assertFalse(resolution.getAlternativePaths().isEmpty(),
            "even with no resolver the reviewer gets options");
    }

    @Test
    @DisplayName("rejects null arguments")
    void rejectsNullArguments() {
        MergeConflictResolver resolver = resolver();
        assertThrows(NullPointerException.class, () -> resolver.resolve((Conflict) null));
        assertThrows(NullPointerException.class, () -> resolver.resolveAll(null));
        assertThrows(NullPointerException.class, () -> resolver.fixPathsFor((Conflict) null));
    }

    @Test
    @DisplayName("offers fix paths for a conflict type without resolving")
    void offersFixPathsByType() {
        List<FixPath> fixPaths = resolver().fixPathsFor(ConflictType.VARIABLE_RENAME);

        assertFalse(fixPaths.isEmpty());
        assertTrue(fixPaths.stream().allMatch(path ->
                path.getConflictType() == ConflictType.VARIABLE_RENAME),
            "fix paths must be typed to the request");
    }

    // ------------------------------------------------------- sticky replay

    @Test
    @DisplayName("replays a sticky decision recorded by a previous run")
    void replaysStickyDecision() {
        Path history = tempDir.resolve(BRANCH);
        Conflict conflict = ConflictFixtures.sample(ConflictType.VARIABLE_RENAME);

        // A human picks branch 1's name and agrees to remember it.
        ConflictResolution decision = ConflictResolution.builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .baseCode(conflict.getBaseCode())
            .branch1Code(conflict.getBranch1Code())
            .branch2Code(conflict.getBranch2Code())
            .resolvedCode(conflict.getBranch1Code())
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
            .kind(ConflictResolution.ResolutionKind.DEFERRED)
            .explanation("Reviewer chose branch 1's name")
            .sticky(true)
            .branchName(BRANCH)
            .build();

        new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .recordDecision(conflict, decision);

        // A later update of the same branch sees the same conflict.
        ConflictResolution replayed = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.DEFERRED, replayed.getKind(),
            "a recorded decision must be replayed, not re-litigated");
        assertEquals(ConflictResolution.ResolutionStrategy.STICKY_REPLAY,
            replayed.getResolutionStrategy());
        assertEquals(conflict.getFilePath(), replayed.getFilePath(),
            "the replayed decision must be re-anchored to the incoming file");
    }

    @Test
    @DisplayName("does not change the decision when replaying for another file")
    void replayUsesIncomingFilePath() {
        Path history = tempDir.resolve(BRANCH);
        Conflict original = ConflictFixtures.sample(ConflictType.IMPORT_ADD);

        new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .recordDecision(original, ConflictResolution.builder()
                .filePath(original.getFilePath())
                .type(original.getType())
                .resolvedCode("import java.util.List;")
                .resolutionStrategy(ConflictResolution.ResolutionStrategy.STICKY_REPLAY)
                .kind(ConflictResolution.ResolutionKind.DEFERRED)
                .sticky(true)
                .branchName(BRANCH)
                .build());

        Conflict elsewhere = original.withFilePath("src/Other.java");
        ConflictResolution replayed = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(history)
            .setTypeContext(TestTypeContexts.jdk())
            .build()
            .resolve(elsewhere);

        assertEquals("src/Other.java", replayed.getFilePath());
        assertTrue(replayed.getKind() == ConflictResolution.ResolutionKind.DEFERRED
                || replayed.getKind() == ConflictResolution.ResolutionKind.AUTO,
            "an identical disagreement in another file may replay, but must be re-anchored");
    }

    @Test
    @DisplayName("does not persist a one-off manual decision")
    void doesNotPersistManualDecision() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE);
        MergeConflictResolver resolver = resolver();

        resolver.recordDecision(conflict, new StructuralChangeConflictResolver().resolve(conflict));

        assertEquals(0, resolver.getHistoryStore().size(),
            "a structural decision must not become a silent policy");
    }

    @Test
    @DisplayName("persists an auto resolution that opted into stickiness")
    void persistsReplayableAutoResolution() {
        MergeConflictResolver resolver = resolver();
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);
        resolver.recordDecision(conflict, ConflictResolution.builder()
            .filePath(conflict.getFilePath())
            .type(conflict.getType())
            .resolvedCode("import java.util.List;")
            .resolutionStrategy(ConflictResolution.ResolutionStrategy.KEEP_BOTH)
            .kind(ConflictResolution.ResolutionKind.AUTO)
            .sticky(true)
            .branchName(BRANCH)
            .build());

        assertEquals(1, resolver.getHistoryStore().size());
    }

    @Test
    @DisplayName("a conflict needing review is re-decided on the next update")
    void doesNotPersistReviewResolution() {
        // METHOD_BODY_CHANGE is a REVIEW type: stickyByDefault() is false, so the
        // judgement is made afresh next time rather than replayed blindly.
        MergeConflictResolver resolver = resolver();
        Conflict conflict = ConflictFixtures.sample(ConflictType.METHOD_BODY_CHANGE);
        ConflictResolution resolution = resolver.resolve(conflict);

        assertEquals(ConflictResolution.ResolutionKind.REVIEW, resolution.getKind());
        assertFalse(resolution.isReplayable(),
            "a syntactic merge of two edits must not become permanent policy");
        assertEquals(0, resolver.getHistoryStore().size(),
            "a REVIEW resolution must not be recorded for replay");
    }

    // ------------------------------------------------------------- reports

    @Test
    @DisplayName("detects and resolves a file in one call")
    void resolvesFileEndToEnd() {
        MergeConflictResolver.MergeReport report = resolver().resolve(
            ConflictFixtures.FILE,
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;\nimport java.time.Instant;");

        assertFalse(report.isClean(), "the import conflict must be detected");
        assertFalse(report.getAutoResolutions().isEmpty(),
            "an import conflict must be resolved automatically");
        assertFalse(report.hasUnresolvedConflicts(),
            "nothing here needs a human");
        assertTrue(report.summarize().contains("auto"), report.summarize());
    }

    @Test
    @DisplayName("reports a clean file when the branches agree")
    void reportsCleanFile() {
        MergeConflictResolver.MergeReport report = resolver().resolve(
            ConflictFixtures.FILE, "import java.util.List;",
            "import java.util.List;", "import java.util.List;");

        assertTrue(report.isClean());
        assertTrue(report.getConflicts().isEmpty());
        assertTrue(report.summarize().contains("no conflicts"), report.summarize());
    }

    @Test
    @DisplayName("flags a structural conflict as needing a human")
    void reportsUnresolvedConflict() {
        MergeConflictResolver.MergeReport report = resolver().resolve(
            ConflictFixtures.FILE,
            "import java.util.List;\nvoid process() { audit(); }\nvoid audit() { }",
            "import java.util.List;\nvoid process() { audit(); charge(); }\nvoid audit() { }",
            "import java.util.List;\nvoid process() { audit(); refund(); }\nvoid audit() { }");

        assertFalse(report.isClean());
        assertTrue(report.hasUnresolvedConflicts(),
            "a structural disagreement must surface as unresolved: " + report.summarize());
        assertFalse(report.getManualResolutions().isEmpty());
        assertFalse(report.getManualResolutions().get(0).getExplanation().isBlank(),
            "an unresolved conflict must explain itself");
    }

    @Test
    @DisplayName("reads the three versions from disk")
    void resolvesFilesFromDisk() throws IOException {
        Path base = tempDir.resolve("Base.java");
        Path branch1 = tempDir.resolve("Branch1.java");
        Path branch2 = tempDir.resolve("Branch2.java");
        Files.writeString(base, "import java.util.List;", StandardCharsets.UTF_8);
        Files.writeString(branch1, "import java.util.List;\nimport java.math.BigDecimal;",
            StandardCharsets.UTF_8);
        Files.writeString(branch2, "import java.util.List;\nimport java.time.Instant;",
            StandardCharsets.UTF_8);

        MergeConflictResolver.MergeReport report =
            resolver().resolveFiles(Path.of("src/Payment.java"), base, branch1, branch2);

        assertEquals("src/Payment.java", report.getFilePath());
        assertFalse(report.getAutoResolutions().isEmpty(),
            "the conflict read from disk must be resolved");
    }

    @Test
    @DisplayName("report exposes its branch and file")
    void reportExposesMetadata() {
        MergeConflictResolver.MergeReport report = resolver().resolve(
            "src/X.java",
            "import java.util.List;",
            "import java.util.List;\nimport java.math.BigDecimal;",
            "import java.util.List;\nimport java.time.Instant;");

        assertEquals("src/X.java", report.getFilePath());
        assertEquals(BRANCH, report.getBranchName());
        assertNotNull(report.getConflicts());
        assertNotNull(report.getResolutions());
        assertFalse(report.summarize().isBlank());
        assertTrue(report.getConflicts().get(0).getDescription().contains("imports"),
            "the detected conflict should describe the import additions: "
                + report.getConflicts().get(0).getDescription());
    }

    @Test
    @DisplayName("report partitions resolutions by kind")
    void reportPartitionsByKind() {
        MergeConflictResolver resolver = resolver();
        List<ConflictResolution> resolutions = List.of(
            resolver.resolve(ConflictFixtures.sample(ConflictType.IMPORT_ADD)),
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE)));
        MergeConflictResolver.MergeReport report = new MergeConflictResolver.MergeReport(
            ConflictFixtures.FILE, List.of(), resolutions, BRANCH);

        assertEquals(1, report.getAutoResolutions().size());
        assertEquals(1, report.getManualResolutions().size());
        assertTrue(report.getReviewResolutions().isEmpty());
    }

    // ------------------------------------------------------- extensibility

    @Test
    @DisplayName("accepts a custom resolver without touching the orchestrator")
    void acceptsCustomResolver() {
        ConflictResolver custom = new AbstractConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return ConflictType.STRUCTURAL_CHANGE;
            }

            @Override
            protected ConflictResolution doResolve(Conflict conflict) {
                return autoResolution(conflict, ConflictResolution.ResolutionStrategy.KEEP_BOTH)
                    .resolvedCode("custom")
                    .explanation("handled by a custom resolver")
                    .build();
            }

            @Override
            protected List<FixPath> describeOptions(Conflict conflict) {
                return List.of();
            }
        };

        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(tempDir.resolve("custom"))
            .setResolvers(List.of(custom))
            .build();

        ConflictResolution resolution =
            resolver.resolve(ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE));

        assertEquals("custom", resolution.getResolvedCode(),
            "a custom resolver must take over its type");
        assertEquals(ConflictResolution.ResolutionKind.AUTO, resolution.getKind());
    }

    @Test
    @DisplayName("addResolver keeps the built-in resolvers")
    void addResolverKeepsDefaults() {
        ConflictResolver custom = new AbstractConflictResolver() {
            @Override
            public ConflictType supportedType() {
                return ConflictType.STRUCTURAL_CHANGE;
            }

            @Override
            protected ConflictResolution doResolve(Conflict conflict) {
                return null;
            }

            @Override
            protected List<FixPath> describeOptions(Conflict conflict) {
                return List.of();
            }
        };

        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName(BRANCH)
            .setHistoryPath(tempDir.resolve("added"))
            .setTypeContext(TestTypeContexts.jdk())
            .addResolver(custom)
            .build();

        assertTrue(resolver.getResolvers().size() > 1,
            "adding a resolver must not discard the built-in ones");
        assertTrue(resolver.resolve(ConflictFixtures.sample(ConflictType.IMPORT_ADD))
                .getKind() == ConflictResolution.ResolutionKind.AUTO,
            "built-in resolutions must still work");
    }

    @Test
    @DisplayName("defaults the history location under .jcodebuddy when unset")
    void defaultsHistoryLocation() {
        MergeConflictResolver resolver = new MergeConflictResolver.Builder()
            .setBranchName("main")
            .setTypeContext(TestTypeContexts.jdk())
            .build();

        assertTrue(resolver.getHistoryPath().toString().replace('\\', '/')
                .endsWith(".jcodebuddy/merge-history/main"),
            "was " + resolver.getHistoryPath());
    }

    @Test
    @DisplayName("create() yields a usable resolver")
    void createYieldsUsableResolver() {
        MergeConflictResolver resolver = MergeConflictResolver.create(TestTypeContexts.jdk());

        assertNotNull(resolver);
        assertFalse(resolver.getResolvers().isEmpty());
        assertSame(ConflictType.IMPORT_ADD, resolver.getResolvers().get(0).supportedType(),
            "the resolver list should be deterministic");
    }

    @Test
    @DisplayName("keeps resolution deterministic across repeated runs")
    void resolutionIsDeterministic() {
        Conflict conflict = ConflictFixtures.sample(ConflictType.IMPORT_ADD);
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            outcomes.add(new MergeConflictResolver.Builder()
                .setBranchName(BRANCH)
                .setHistoryPath(tempDir.resolve("run-" + i))
                .setTypeContext(TestTypeContexts.jdk())
                .build()
                .resolve(conflict)
                .getResolvedCode());
        }

        assertEquals(1, outcomes.stream().distinct().count(),
            "the same conflict must resolve the same way every time");
    }
}
