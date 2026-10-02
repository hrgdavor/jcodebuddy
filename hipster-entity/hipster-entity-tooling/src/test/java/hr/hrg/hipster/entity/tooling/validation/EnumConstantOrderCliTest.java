package hr.hrg.hipster.entity.tooling.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The R1 order checker's CLI and its exit-code contract (plan.dsflash § 6.3/1.16, § 4.6/R1.3).
 *
 * <p>Task 1.16 exists because the rule alone cannot gate anything: the rule is a library call, and a
 * build or a PR check needs a process that reads a <strong>baseline from the repository</strong> and
 * returns a status. `EnumConstantOrderCheckerTest` covers the comparison; this covers the part that
 * decides 0, 1 or 2.</p>
 *
 * <p>The fixture is a real (if tiny) git repository, built in a temporary directory. A stub would
 * test the flag parsing and nothing else, and the baseline is precisely the part that cannot be
 * stubbed: the plan requires it to be reproducible from the repository — a git ref or a diff file,
 * never "the previous run" — which is also why this is the one tooling test that shells out to
 * {@code git}.</p>
 */
class EnumConstantOrderCliTest {

    private static final String MARKED =
            "// {enabled:true, entityFieldEnum:true, blockMarker: \"implicit\"}\n";

    private static final String UNMARKED =
            "// {enabled:true, blockMarker: \"implicit\"}\n";

    /** A field enum of the shape the checker guards: the marker plus a constant list. */
    private static String enumSource(String header, String... constants) {
        StringBuilder sb = new StringBuilder(header);
        sb.append("package example;\n");
        sb.append("public enum PersonSummary_ {\n");
        for (int i = 0; i < constants.length; i++) {
            sb.append("    ").append(constants[i]).append(i == constants.length - 1 ? ";\n" : ",\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static Path writeTree(String source) throws Exception {
        Path root = Files.createTempDirectory("order-cli-repo");
        Path packageDir = root.resolve("example");
        Files.createDirectories(packageDir);
        Files.writeString(packageDir.resolve("PersonSummary_.java"), source);
        return root;
    }

    /** Commits {@code source} as the baseline revision and returns the repository. */
    private static Path gitRepo(String source) throws Exception {
        Path root = writeTree(source);
        git(root, "init", "-q");
        git(root, "add", "-A");
        // The identity is passed inline: a CI machine has none configured, and a test that needs one
        // would fail there for a reason that has nothing to do with the checker.
        git(root, "-c", "user.email=test@example.invalid", "-c", "user.name=Test", "commit", "-qm", "baseline");
        return root;
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        Assertions.assertEquals(0, process.waitFor(),
                "git " + String.join(" ", args) + " failed: " + output);
    }

    private static void replaceEnum(Path repo, String source) throws Exception {
        Files.writeString(repo.resolve("example/PersonSummary_.java"), source);
    }

    @Test
    void anAppendedConstantIsAccepted() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(MARKED, "id", "firstName", "lastName", "email"));

        Assertions.assertEquals(0, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--baseline", "HEAD" }),
                "appending at the end is the one allowed change (R1)");
    }

    @Test
    void aRemovedConstantFailsWithExitOne() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(MARKED, "id", "lastName"));

        Assertions.assertEquals(1, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--baseline", "HEAD" }),
                "a removal is a data-losing change, so the process status must be non-zero: this is "
                        + "what makes the checker usable as a build gate");
    }

    @Test
    void aShuffledConstantFailsEvenThoughTheSetIsUnchanged() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(MARKED, "id", "lastName", "firstName"));

        Assertions.assertEquals(1, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--baseline", "HEAD" }),
                "the same constants in a different order read every value under the wrong ordinal");
    }

    @Test
    void anUnmarkedBaselineEnumIsSkipped() throws Exception {
        Path repo = gitRepo(enumSource(UNMARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(UNMARKED, "id", "lastName"));

        Assertions.assertEquals(0, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--baseline", "HEAD" }),
                "opt-in by absence (R1.2): without `entityFieldEnum:true` in the BASELINE revision the "
                        + "enum has no committed ledger, so the bootstrap commit is not a mass removal");
    }

    /**
     * The comparison is driven by what the <strong>baseline revision tracks</strong>, not by the
     * files the working tree happens to contain.
     *
     * <p>This is the answer to the question an earlier notes entry left open (D-13): a regeneration
     * probe that leaves copies of generated enums in an untracked directory cannot make the checker
     * report a removal, because a file the baseline does not contain is never a comparison — an
     * untracked copy has nothing to be the "after" of. The exclusion list therefore does not need a
     * {@code tmp/} entry; the property that matters is this one, and it is asserted rather than
     * argued.</p>
     */
    @Test
    void untrackedCopiesInTheWorkingTreeAreNotCompared() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));

        // The debris: an untracked copy of the same enum with a constant removed.
        Path debris = repo.resolve("tmp/regen3/example");
        Files.createDirectories(debris);
        Files.writeString(debris.resolve("PersonSummary_.java"), enumSource(MARKED, "id", "lastName"));

        Assertions.assertEquals(0, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--baseline", "HEAD" }),
                "an untracked file is not part of the baseline, so it cannot violate it");
    }

    /**
     * Writes a diff between the committed baseline and the working tree, and fails if git produced
     * nothing.
     *
     * <p>The emptiness check is not decoration. Every {@code --diff} case below is a comparison
     * against text recovered from this file, so a diff that silently came out empty would make the
     * baseline identical to the target and every one of them would pass for the wrong reason — the
     * same vacuous-pass trap {@code DivergenceKindTest.damaged} exists to close.</p>
     */
    private static Path writeDiff(Path repo) throws Exception {
        // No pathspec: `-- .` is resolved against the Maven process's working directory, not `-C`, so
        // it filtered every change away and produced an empty diff (which the assertion below caught).
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toAbsolutePath().toString(),
                "--no-pager", "diff"));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String diff = new String(process.getInputStream().readAllBytes());
        Assertions.assertEquals(0, process.waitFor(), "git diff failed: " + diff);
        Assertions.assertFalse(diff.isBlank(), "the fixture must produce a non-empty diff");
        Path diffFile = Files.createTempFile("enum-order", ".patch");
        Files.writeString(diffFile, diff);
        return diffFile;
    }

    @Test
    void aDiffBaselineAcceptsAnAppend() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(MARKED, "id", "firstName", "lastName", "email"));
        Path diff = writeDiff(repo);

        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        int exit;
        try (java.io.PrintStream err = new java.io.PrintStream(errors, true, java.nio.charset.StandardCharsets.UTF_8)) {
            exit = EnumConstantOrderCli.run(new String[] {
                    "--repo", repo.toString(), "--diff", diff.toString() }, err);
        }

        Assertions.assertEquals(0, exit,
                "a PR check has the diff, not the branch: reversing it recovers the same baseline a "
                        + "git ref would have given, so an append must be accepted. Diagnostics: "
                        + errors);
    }

    @Test
    void aDiffBaselineCatchesAReorder() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(MARKED, "id", "lastName", "firstName"));
        Path diff = writeDiff(repo);

        Assertions.assertEquals(1, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--diff", diff.toString() }),
                "and it is the same comparison, so a shuffled constant still fails");
    }

    @Test
    void aDiffThatCannotBeAppliedIsAUsageErrorNotAMisReadBaseline() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id", "firstName", "lastName"));
        replaceEnum(repo, enumSource(MARKED, "id", "lastName"));
        Path diff = writeDiff(repo);

        // A hunk that claims lines far past the end of the file: git cannot place it, so the baseline
        // cannot be recovered. Editing the *context* would not be a reliable negative — git applies with
        // fuzz when the hunk's text is still findable nearby, which is the failure direction this case
        // exists to exclude.
        String text = Files.readString(diff);
        Assertions.assertTrue(text.contains("@@"), "the fixture diff must have a hunk header");
        Files.writeString(diff, text.replaceFirst("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@", "@@ -999,7 +999,7 @@"));

        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        int exit;
        try (java.io.PrintStream err = new java.io.PrintStream(errors, true, java.nio.charset.StandardCharsets.UTF_8)) {
            exit = EnumConstantOrderCli.run(new String[] {
                    "--repo", repo.toString(), "--diff", diff.toString() }, err);
        }

        Assertions.assertEquals(2, exit,
                "a baseline that could not be applied exactly is refused: reporting violations against "
                        + "text nobody wrote is worse than reporting nothing");
        Assertions.assertTrue(errors.toString(java.nio.charset.StandardCharsets.UTF_8)
                        .contains("could not be reverse-applied"),
                "and the refusal says the diff did not apply: " + errors);
    }

    @Test
    void aDiffFileThatDoesNotExistIsAUsageError() throws Exception {
        Path repo = writeTree(enumSource(MARKED, "id"));

        Assertions.assertEquals(2, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--diff", repo.resolve("nope.patch").toString() }),
                "an unreadable baseline is an environment error, not a violation");
    }

    @Test
    void aMissingBaselineIsAUsageError() throws Exception {
        Path repo = writeTree(enumSource(MARKED, "id"));

        Assertions.assertEquals(2, EnumConstantOrderCli.run(new String[] { "--repo", repo.toString() }),
                "there is no default baseline: 'the previous run' would not be reproducible");
    }

    @Test
    void namingBothBaselinesIsRefusedRatherThanResolved() throws Exception {
        Path repo = gitRepo(enumSource(MARKED, "id"));

        // The guard runs before either baseline is read, so the diff path deliberately does not exist:
        // the assertion is that the combination is refused, not that the file was opened.
        Assertions.assertEquals(2, EnumConstantOrderCli.run(new String[] {
                "--repo", repo.toString(), "--baseline", "HEAD", "--diff", "no-such.patch" }),
                "--baseline and --diff are two ways to name the same thing; silently preferring one "
                        + "would make the reported baseline depend on flag order");
    }
}
