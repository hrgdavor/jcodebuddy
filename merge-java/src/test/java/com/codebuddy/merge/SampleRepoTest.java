// {@link com.codebuddy.merge.SampleRepoTest} Verifies a fixture generated as a real git repository.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A fixture turned into a real repository by
 * {@code scripts/git-sample/sample-repo.js}, held to the two things that make it
 * worth having.
 *
 * <p>The first is shape: the base commit must really be the common ancestor of
 * both sides, because every reading of "what changed" is a comparison against it,
 * and a base that is not shared would silently misattribute both sides' edits.
 *
 * <p>The second is that {@link MergeWorkflow} must work against it unchanged. The
 * script writes the last-sync marker the module reads, so if this test passes, the
 * marker's format, the tracking configuration and the three trees are all what the
 * module expects - the generator is not a parallel, drifting implementation of the
 * same idea.
 *
 * <p>Skipped when Bun is unavailable, matching how the renderer test treats it.
 */
class SampleRepoTest {

    private static final String CASE_NAME = "import-add-both";
    private static final String FILE = "PaymentProcessor.java";

    @TempDir
    Path tempDir;

    private static boolean bunAvailable() {
        try {
            Process process = new ProcessBuilder("bun", "--version")
                .redirectErrorStream(true)
                .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static Path script() {
        return Path.of("scripts", "git-sample", "sample-repo.js").toAbsolutePath();
    }

    /**
     * Generate the sample repository into the test's temporary directory.
     */
    private Path generateSampleRepo() throws Exception {
        return generateSampleRepo(CASE_NAME);
    }

    /**
     * Generate one named fixture into the test's temporary directory.
     */
    private Path generateSampleRepo(String fixtureName) throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed; the generator is not exercised");
        Path generator = script();
        assumeTrue(Files.isRegularFile(generator), "generator script not found at " + generator);

        Path target = tempDir.resolve("repo-" + fixtureName);
        Process process = new ProcessBuilder("bun", "run", generator.toString(),
            fixtureName, "--out", target.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), "the generator failed: " + output);

        return target;
    }

    @Test
    @DisplayName("the generated repository is a working tree with all three sides in it")
    void generatesARealRepository() throws Exception {
        Path repository = generateSampleRepo();

        assertTrue(Files.isDirectory(repository.resolve(".git")), "a real git directory");
        assertTrue(Files.isRegularFile(repository.resolve(FILE)),
            "ours is checked out, with the .txt marker removed: "
                + Files.readString(repository.resolve(FILE)));
        assertTrue(Files.readString(repository.resolve(FILE)).contains("import java.time.Instant;"),
            "the working tree must hold our side, not the base");

        // The fixture's diffs travel with the repository, as documentation to
        // compare a resolution against.
        assertTrue(Files.isRegularFile(repository.resolve("expected/ours.diff")));
        assertTrue(Files.isRegularFile(repository.resolve("expected/theirs.diff")));
    }

    @Test
    @DisplayName("the base commit is the common ancestor of both sides")
    void baseCommitIsTheCommonAncestor() throws Exception {
        Path repository = generateSampleRepo();

        try (Repository git = new FileRepositoryBuilder()
            .setGitDir(repository.resolve(".git").toFile()).setMustExist(true).build()) {

            org.eclipse.jgit.lib.ObjectId base = git.resolve("refs/heads/base");
            assertNotNull(base, "the base commit is reachable by name, so it can be inspected");

            try (RevWalk walk = new RevWalk(git)) {
                RevCommit ours = walk.parseCommit(git.resolve("refs/heads/feature"));
                RevCommit theirs = walk.parseCommit(git.resolve("refs/heads/upstream"));

                assertEquals(1, ours.getParentCount(), "ours is one commit above the base");
                assertEquals(base, ours.getParent(0));
                assertEquals(base, theirs.getParent(0),
                    "both sides must start from the one shared base commit");
                assertEquals(1, theirs.getParentCount(),
                    "the base is the merge base, not an ancestor of one side only");
            }
        }
    }

    @Test
    @DisplayName("git itself conflicts on the generated repository")
    void gitConflictsOnTheGeneratedRepository() throws Exception {
        Path repository = generateSampleRepo();

        Process merge = new ProcessBuilder("git", "merge-tree", "--write-tree",
            "feature", "upstream")
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
        String report = new String(merge.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(1, merge.waitFor(),
            "this fixture is one git conflicts on, so a merge tool sees the problem: " + report);
        assertTrue(report.contains("CONFLICT"), report);
        assertTrue(report.contains(FILE), report);
    }

    @Test
    @DisplayName("the last-sync marker pins the base commit by id, not by a moving ref name")
    void markerPinsTheBaseCommit() throws Exception {
        Path repository = generateSampleRepo();
        Path marker = repository.resolve(".jcodebuddy").resolve("merge-history")
            .resolve("feature").resolve("last-sync");
        assumeTrue(Files.isRegularFile(marker), "no marker at " + marker);

        LastSyncMarker read = LastSyncMarker.read(marker.getParent()).orElseThrow();
        assertEquals(40, read.upstreamCommit().length(),
            "the marker must hold a commit id: a branch name resolves to whatever that "
                + "branch points at now, which is not the last-synced state. Was: "
                + read.upstreamCommit());
        assertEquals("upstream", read.upstreamRef(), "and name the ref for a human");
        assertEquals(gitLine(repository, "rev-parse", "refs/heads/base"), read.upstreamCommit(),
            "the pinned commit must be the fixture's base, not the upstream tip");
        assertEquals(read.upstreamCommit(), read.requireCommitId(marker.getParent()).getName(),
            "and the module's own validation must accept what the generator wrote");
    }

    @Test
    @DisplayName("a marker naming a branch is refused, so no conflicting fixture is called clean")
    void branchNameMarkerIsRefusedRatherThanCalledClean() throws Exception {
        Path repository = generateSampleRepo();

        // This is the exact mistake the generator made first, and the fixture it was made
        // on: a real repository with a real conflict in it. Believing the marker made the
        // base the upstream tip, and the workflow then reported this fixture as clean.
        Path marker = repository.resolve(".jcodebuddy").resolve("merge-history")
            .resolve("feature").resolve("last-sync");
        Files.writeString(marker, "upstreamCommit = upstream\nupstreamRef = upstream\n",
            StandardCharsets.UTF_8);

        SyncMarkerException failure = assertThrows(SyncMarkerException.class, () ->
            MergeWorkflow.open(repository).upstream("upstream").path(FILE).run());

        assertTrue(failure.getMessage().contains("'upstream'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("clean"),
            "the message must explain what believing it would have cost: "
                + failure.getMessage());
    }

    @Test
    @DisplayName("MergeWorkflow reads the generated repository and resolves the import conflict")
    void mergeWorkflowRunsAgainstTheGeneratedRepository() throws Exception {
        Path repository = generateSampleRepo();
        String baseCommit = markerOf(repository).upstreamCommit();

        MergeWorkflow.Result result = MergeWorkflow.open(repository)
            .upstream("upstream")
            .path(FILE)
            .run();

        assertEquals("feature", result.branchName(), "the branch is discovered, not configured");
        assertTrue(result.baseSource().contains("last-sync marker"),
            "the marker the generator wrote must be the one the module reads: "
                + result.baseSource());
        assertTrue(result.baseSource().contains(baseCommit.substring(0, 12)),
            "and it must name the base commit, not the upstream tip: " + result.baseSource());

        // The import conflict is real and is resolved automatically.
        assertEquals(1, result.summary().autoResolutions(),
            "the import merge must be recognised: " + result.describe());

        // But this fixture's sides also each rewrote the class comment, so the file
        // still needs a human - and that is the honest outcome, not a failure.
        assertTrue(result.filesNeedingAttention().contains(FILE),
            "the fixture's divergent comment is a manual conflict: " + result.describe());
        assertTrue(result.summary().manualResolutions() >= 1, result.describe());
        assertEquals(1, result.exitCode(),
            "a file needing a human is not reported as success: " + result.describe());
    }

    @Test
    @DisplayName("each commit's blob is the fixture file, byte for byte")
    void everyCommitIsTheFixtureFile() throws Exception {
        Path repository = generateSampleRepo();
        Path fixture = Path.of("src", "test", "resources", "fixtures", CASE_NAME);

        for (String side : List.of("base", "ours", "theirs")) {
            String fromGit = committed(repository, "refs/heads/" + refFor(side));
            String fromFixture = Files.readString(
                fixture.resolve(side).resolve(FILE + ".txt"), StandardCharsets.UTF_8);
            assertEquals(fromFixture, fromGit,
                "the " + side + " commit must hold the fixture's " + side + " file, not a "
                    + "re-rendered copy of it: a sample repository is only a simulation if "
                    + "the bytes are the ones the module's tests use. " + firstDifference(
                        fromFixture, fromGit));
        }

        assertEquals(committed(repository, "refs/heads/feature"),
            Files.readString(repository.resolve(FILE), StandardCharsets.UTF_8),
            "and the working tree must hold our side, so a tool started in the directory "
                + "sees the same file the commit holds");
    }

    @Test
    @DisplayName("git merges the clean fixture without a conflict, though the sides disagree")
    void cleanFixtureHasNoGitConflict() throws Exception {
        Path repository = generateSampleRepo("import-add-remove-same");

        Process merge = new ProcessBuilder("git", "merge-tree", "--write-tree",
            "feature", "upstream")
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
        String report = new String(merge.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, merge.waitFor(),
            "this is the case git resolves silently, which is the whole reason the module "
                + "exists: " + report);
        assertTrue(!report.contains("CONFLICT"),
            "and it must say so, rather than merging with markers nobody looked at: " + report);
    }

    /** The branch that holds a side of the fixture. */
    private static String refFor(String side) {
        return switch (side) {
            case "base" -> "base";
            case "ours" -> "feature";
            case "theirs" -> "upstream";
            default -> throw new IllegalArgumentException(side);
        };
    }

    /**
     * A one-line account of where two texts diverge.
     *
     * A bare assertEquals on a whole file prints two long strings whose difference
     * the reader has to find; naming the offset, the line and the two characters
     * says what actually changed.
     */
    private static String firstDifference(String expected, String actual) {
        int limit = Math.min(expected.length(), actual.length());
        for (int index = 0; index < limit; index++) {
            if (expected.charAt(index) != actual.charAt(index)) {
                int line = (int) expected.substring(0, index).chars()
                    .filter(character -> character == '\n').count() + 1;
                return "First difference at line " + line + ", offset " + index + ": fixture has "
                    + describe(expected.charAt(index)) + ", the commit has "
                    + describe(actual.charAt(index)) + ".";
            }
        }
        if (expected.length() != actual.length()) {
            return "The texts agree for " + limit + " characters; the fixture is "
                + expected.length() + " and the commit is " + actual.length() + ".";
        }
        return "The texts are equal.";
    }

    private static String describe(char character) {
        return switch (character) {
            case '\r' -> "a carriage return";
            case '\n' -> "a newline";
            case '\t' -> "a tab";
            default -> "'" + character + "'";
        };
    }

    /** The marker the generator wrote for the feature branch. */
    private static LastSyncMarker markerOf(Path repository) {
        return LastSyncMarker.read(repository.resolve(".jcodebuddy").resolve("merge-history")
            .resolve("feature")).orElseThrow();
    }

    /** A ref's blob content for the fixture's path. */
    private static String committed(Path repository, String ref) throws Exception {
        return git(repository, "show", ref + ":" + FILE);
    }

    /**
     * Run git in the generated repository and return its output **verbatim**.
     *
     * No trimming: one of these calls reads a file's content back out of git, and
     * the trailing newline is part of that content. Callers that want a one-line
     * value use {@link #gitLine}.
     */
    private static String git(Path repository, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
        byte[] output = process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(),
            "git " + String.join(" ", args) + " failed: "
                + new String(output, StandardCharsets.UTF_8));
        return new String(output, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
    }

    /** Run git and return the single value it printed, without the line ending. */
    private static String gitLine(Path repository, String... args) throws Exception {
        return git(repository, args).trim();
    }

    @Test
    @DisplayName("the generator lists the fixture it can simulate")
    void listsFixtures() throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed");
        Path generator = script();
        assumeTrue(Files.isRegularFile(generator), "generator script not found");

        Process process = new ProcessBuilder("bun", "run", generator.toString(), "--list")
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains(CASE_NAME), output);
        assertTrue(output.contains("import-add-remove-same"),
            "every fixture in the tree must be discoverable: " + output);
    }

    @Test
    @DisplayName("refusing to overwrite an existing directory keeps a build deliberate")
    void refusesToOverwriteWithoutForce() throws Exception {
        Path repository = generateSampleRepo();

        Process again = new ProcessBuilder("bun", "run", script().toString(),
            CASE_NAME, "--out", repository.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(again.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(1, again.waitFor(), "silently replacing a repository is not allowed: " + output);
        assertTrue(output.contains("--force"), output);
    }

    @Test
    @DisplayName("a fixture that does not exist is reported, not guessed at")
    void reportsAMissingFixture() throws Exception {
        assumeTrue(bunAvailable(), "bun is not installed");
        Path generator = script();
        assumeTrue(Files.isRegularFile(generator), "generator script not found");

        Process process = new ProcessBuilder("bun", "run", generator.toString(),
            "no-such-fixture", "--out", tempDir.resolve("nope").toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(1, process.waitFor(), output);
        assertTrue(output.contains("no fixture directory"), output);
    }

    @Test
    @DisplayName("every fixture directory can be simulated, not just the one in the tests")
    void everyFixtureIsSimulatable() throws Exception {
        Path fixtures = Path.of("src", "test", "resources", "fixtures").toAbsolutePath();
        assumeTrue(bunAvailable(), "bun is not installed");
        assumeTrue(Files.isDirectory(fixtures), "no fixture tree at " + fixtures);
        assumeTrue(Files.isRegularFile(script()), "generator script not found");

        List<Path> cases;
        try (Stream<Path> entries = Files.list(fixtures)) {
            cases = entries.filter(Files::isDirectory).sorted().toList();
        }
        assertTrue(!cases.isEmpty(), "the fixture tree must not be empty");

        for (Path directory : cases) {
            String name = directory.getFileName().toString();
            Path target = tempDir.resolve("all").resolve(name);
            Process process = new ProcessBuilder("bun", "run", script().toString(),
                name, "--out", target.toString())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

            assertEquals(0, process.waitFor(), "fixture '" + name + "' is not simulatable: " + output);
            assertTrue(Files.isDirectory(target.resolve(".git")),
                "fixture '" + name + "' produced no repository");
        }
    }
}