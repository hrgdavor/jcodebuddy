// {@link com.codebuddy.merge.MergeWorkflowTest} Tests the JGit workflow against a real repository.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workflow is the only part of the module that writes to a working tree, so it
 * is exercised against a real repository built with JGit in a temporary directory.
 *
 * <p>The fixture is the case the module exists for: a long-lived branch whose base
 * has diverged only by adding imports.
 */
class MergeWorkflowTest {

    private static final String FILE = "Payment.java";

    private static final String BASE_CONTENT =
        "import java.util.List;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "    }\n"
            + "}\n";

    private static final String UPSTREAM_CONTENT =
        "import java.util.List;\n"
            + "import java.time.Instant;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "    }\n"
            + "}\n";

    private static final String FEATURE_CONTENT =
        "import java.util.List;\n"
            + "import java.math.BigDecimal;\n"
            + "class Payment {\n"
            + "    void a() {\n"
            + "    }\n"
            + "}\n";

    @TempDir
    Path repositoryDir;

    /**
     * Build a repository where {@code upstream} and {@code feature} both add a
     * different import to the same file, on top of a shared base commit.
     */
    private void buildRepository() throws GitAPIException, IOException {
        try (Git git = Git.init().setDirectory(repositoryDir.toFile()).call()) {
            git.getRepository().getConfig().setString("user", null, "name", "Test");
            git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
            git.getRepository().getConfig().save();

            Path file = repositoryDir.resolve(FILE);
            Files.writeString(file, BASE_CONTENT, StandardCharsets.UTF_8);
            git.add().addFilepattern(FILE).call();
            org.eclipse.jgit.revwalk.RevCommit base =
                git.commit().setMessage("base").setSign(false).call();

            // Name the initial branch explicitly, then branch from the base commit
            // so the two sides share a merge base rather than one containing the
            // other.
            git.branchCreate().setName("main").setForce(true)
                .setStartPoint(base).call();

            git.checkout().setCreateBranch(true).setName("upstream")
                .setStartPoint(base).call();
            Files.writeString(file, UPSTREAM_CONTENT, StandardCharsets.UTF_8);
            git.add().addFilepattern(FILE).call();
            git.commit().setMessage("upstream adds Instant").setSign(false).call();

            git.checkout().setCreateBranch(true).setName("feature")
                .setStartPoint(base).call();
            Files.writeString(file, FEATURE_CONTENT, StandardCharsets.UTF_8);
            git.add().addFilepattern(FILE).call();
            git.commit().setMessage("feature adds BigDecimal").setSign(false).call();
        }
    }

    private String fileContent() throws IOException {
        return Files.readString(repositoryDir.resolve(FILE), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("discovers the branch, the merge base and the conflicting file")
    void discoversRepositoryState() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream")
            .path(FILE)
            .run();

        assertEquals("feature", result.branchName(),
            "the current branch must be discovered, not configured");
        assertNotNull(result.mergeBase(), "a merge base must be found");
        assertEquals(12, result.mergeBase().length(), "the base should be a short id");
        assertTrue(result.dryRun(), "the workflow must be dry by default");
    }

    @Test
    @DisplayName("resolves the conflicting file from the object database")
    void resolvesFromObjectDatabase() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream")
            .path(FILE)
            .run();

        assertEquals(1, result.filesResolved().size(),
            "the import-only conflict must be resolved: " + result.describe());
        assertTrue(result.filesNeedingAttention().isEmpty(),
            "nothing here needs a human: " + result.describe());
        assertEquals(0, result.exitCode());
        assertTrue(result.summary().autoResolutions() >= 1);
    }

    @Test
    @DisplayName("writes nothing in a dry run")
    void dryRunWritesNothing() throws Exception {
        buildRepository();
        String before = fileContent();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream")
            .path(FILE)
            .dryRun(true)
            .run();

        assertTrue(result.dryRun());
        assertEquals(before, fileContent(),
            "a dry run must leave the working tree untouched");
    }

    @Test
    @DisplayName("writes the merged imports when writes are permitted")
    void writesWhenPermitted() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream")
            .path(FILE)
            .dryRun(false)
            .reportTo(repositoryDir.resolve(".jcodebuddy/metadata/merge-report.json"))
            .run();

        assertFalse(result.dryRun());
        assertEquals(1, result.filesResolved().size(), result.describe());

        String merged = fileContent();
        assertTrue(merged.contains("import java.math.BigDecimal;"),
            "ours must survive: " + merged);
        assertTrue(merged.contains("import java.time.Instant;"),
            "theirs must survive: " + merged);
        assertTrue(merged.contains("import java.util.List;"),
            "the shared import must survive: " + merged);
        assertTrue(Files.isRegularFile(repositoryDir.resolve(".jcodebuddy/metadata/merge-report.json")),
            "the report must be written where asked");
    }

    @Test
    @DisplayName("is idempotent: a second run has nothing left to do")
    void secondRunIsIdempotent() throws Exception {
        buildRepository();

        MergeWorkflow.open(repositoryDir).upstream("upstream").path(FILE).dryRun(false).run();
        String afterFirst = fileContent();

        MergeWorkflow.Result second = MergeWorkflow.open(repositoryDir)
            .upstream("upstream").path(FILE).dryRun(false).run();

        assertEquals(afterFirst, fileContent(),
            "resolving an already-resolved file must change nothing");
        assertEquals(0, second.exitCode(), second.describe());
    }

    @Test
    @DisplayName("skips a path that does not exist in either side")
    void skipsUnknownPath() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream")
            .path("DoesNotExist.java")
            .run();

        assertEquals(1, result.filesSkipped().size(), result.describe());
        assertEquals(1, result.exitCode(),
            "an unusable path must not be reported as success");
    }

    @Test
    @DisplayName("reports honestly when there is no upstream to compare against")
    void reportsMissingUpstream() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("refs/heads/does-not-exist")
            .path(FILE)
            .run();

        assertTrue(result.filesResolved().isEmpty());
        assertTrue(result.describe().contains("no upstream"),
            "the reason must be stated: " + result.describe());
    }

    @Test
    @DisplayName("names the tracking branch when upstream is left at HEAD")
    void namesTrackingBranchByDefault() throws Exception {
        buildRepository();

        // Configure feature to track upstream, then let the workflow find it.
        try (Git git = Git.open(repositoryDir.toFile())) {
            git.getRepository().getConfig().setString("branch", "feature", "remote", ".");
            git.getRepository().getConfig().setString("branch", "feature", "merge",
                "refs/heads/upstream");
            git.getRepository().getConfig().save();
        }

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .path(FILE)
            .run();

        assertEquals("feature", result.branchName());
        assertTrue(result.upstreamRef().contains("upstream"),
            "the tracking branch should be named: " + result.upstreamRef());
    }

    @Test
    @DisplayName("a result describes itself for a merge log")
    void resultDescribesItself() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream").path(FILE).run();

        String description = result.describe();
        assertTrue(description.contains("branch feature"), description);
        assertTrue(description.contains("resolved:"), description);
        assertTrue(description.contains("conflicts:"), description);
    }

    @Test
    @DisplayName("the first sync uses the merge base, because nothing is recorded yet")
    void firstSyncUsesMergeBase() throws Exception {
        buildRepository();

        MergeWorkflow.Result result = MergeWorkflow.open(repositoryDir)
            .upstream("upstream").path(FILE).run();

        assertTrue(result.baseSource().contains("merge base"),
            "with no sync recorded, the common ancestor is the only stand-in: "
                + result.baseSource());
    }

    @Test
    @DisplayName("a later sync resolves against the recorded last-synced upstream")
    void laterSyncUsesRecordedMarker() throws Exception {
        buildRepository();

        // The first run records what this branch has seen.
        MergeWorkflow.open(repositoryDir).upstream("upstream").path(FILE).run();

        MergeWorkflow.Result second = MergeWorkflow.open(repositoryDir)
            .upstream("upstream").path(FILE).run();

        assertTrue(second.baseSource().contains("last-sync marker"),
            "the second sync must use the recorded marker, not a recomputed merge base: "
                + second.baseSource());
    }

    @Test
    @DisplayName("the marker is written where the decision history lives")
    void markerIsWrittenBesideDecisions() throws Exception {
        buildRepository();

        MergeWorkflow.open(repositoryDir).upstream("upstream").path(FILE).run();

        Path branchHistory = repositoryDir.resolve(".jcodebuddy")
            .resolve("merge-history").resolve("feature");
        assertTrue(Files.isRegularFile(branchHistory.resolve(LastSyncMarker.FILE_NAME)),
            "the marker belongs with the branch's decisions: " + branchHistory);

        LastSyncMarker marker = LastSyncMarker.read(branchHistory).orElseThrow();
        assertEquals("upstream", marker.upstreamRef());
        assertTrue(marker.note().contains("dry run"),
            "a dry run records what it inspected, and says so: " + marker.note());
    }

    @Test
    @DisplayName("reports which reference point it resolved against")
    void reportsTheBaseUsed() throws Exception {
        buildRepository();

        String description = MergeWorkflow.open(repositoryDir)
            .upstream("upstream").path(FILE).run().describe();

        assertTrue(description.contains("base:"), description);
    }

    @Test
    @DisplayName("distinctPaths deduplicates the reported paths")
    void distinctPathsDeduplicates() {
        MergeConflictResolver.MergeReport first = MergeConflictResolver.create(TestTypeContexts.jdk())
            .resolve("A.java", "import a.A;",
                "import a.A;\nimport b.B;", "import a.A;\nimport c.C;");
        MergeConflictResolver.MergeReport second = MergeConflictResolver.create(TestTypeContexts.jdk())
            .resolve("A.java", "import a.A;",
                "import a.A;\nimport d.D;", "import a.A;\nimport e.E;");

        assertEquals(1, MergeWorkflow.distinctPaths(java.util.List.of(first, second)).size());
    }
}
