package hr.hrg.hipster.entity.tooling.validation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Recovering a baseline by reverse-applying a unified diff (plan.dsflash § 4.6/R1.3).
 *
 * <p>The cases are the three shapes a real change takes in a field enum — an append (the one allowed
 * edit), a reorder, and a removal — plus the refusals that keep a mis-applied baseline from being
 * reported as a violation. They run through git's real diff output rather than hand-written patch
 * text, because that is what the CLI will be handed, and the format's details (context-line spelling,
 * line endings, hunk arithmetic) are exactly what an earlier hand-written applier got wrong three
 * times over.</p>
 */
class UnifiedDiffTest {

    private static final String BASELINE = """
            // {enabled:true, entityFieldEnum:true, blockMarker: "implicit"}
            package example;
            public enum PersonSummary_ {
                id,
                firstName,
                lastName;
            }
            """;

    /** A real git repository with the baseline committed and the given text in the working tree. */
    private static Path repo(String workingTree) throws Exception {
        Path root = Files.createTempDirectory("unified-diff-repo");
        Path pkg = root.resolve("example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("PersonSummary_.java"), BASELINE);
        git(root, "init", "-q");
        git(root, "add", "-A");
        git(root, "-c", "user.email=t@example.invalid", "-c", "user.name=T", "commit", "-qm", "baseline");
        Files.writeString(pkg.resolve("PersonSummary_.java"), workingTree);
        return root;
    }

    /** The diff of the working tree against HEAD, as the CLI's {@code --diff} baseline receives it. */
    private static String diffOf(Path root) throws Exception {
        String diff = gitCapture(root, "--no-pager", "diff");
        Assertions.assertFalse(diff.isBlank(), "the fixture must produce a non-empty diff");
        return diff;
    }

    private static void git(Path repo, String... args) throws Exception {
        gitCapture(repo, args);
    }

    private static String gitCapture(Path repo, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", repo.toAbsolutePath().toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        Assertions.assertEquals(0, process.waitFor(), "git " + String.join(" ", args) + " failed: " + output);
        return output;
    }

    @Test
    void reversingAnAppendRecoversTheOriginalConstantList() throws Exception {
        Path root = repo(BASELINE.replace("lastName;", "lastName,\n    email;"));

        Map<String, String> baseline = UnifiedDiff.baselineFrom(diffOf(root), root);

        String recovered = baseline.get("example/PersonSummary_.java");
        Assertions.assertNotNull(recovered, baseline.toString());
        Assertions.assertTrue(recovered.contains("lastName;"), "the replaced line comes back: " + recovered);
        Assertions.assertFalse(recovered.contains("email"), "and the added one goes away: " + recovered);
        Assertions.assertEquals(BASELINE.replace("\r\n", "\n"), recovered.replace("\r\n", "\n"),
                "the recovered text is exactly the committed baseline");
    }

    @Test
    void reversingAReorderRecoversTheOriginalOrder() throws Exception {
        Path root = repo(BASELINE.replace("firstName,\n    lastName;", "lastName,\n    firstName;"));

        String recovered = UnifiedDiff.baselineFrom(diffOf(root), root)
                .get("example/PersonSummary_.java");

        Assertions.assertTrue(recovered.indexOf("firstName") < recovered.indexOf("lastName"),
                "the baseline order is restored, which is what the checker compares against: " + recovered);
    }

    @Test
    void reversingARemovalRestoresTheRemovedConstant() throws Exception {
        Path root = repo(BASELINE.replace("    firstName,\n", ""));

        String recovered = UnifiedDiff.baselineFrom(diffOf(root), root)
                .get("example/PersonSummary_.java");

        Assertions.assertTrue(recovered.contains("firstName"), recovered);
        Assertions.assertTrue(recovered.contains("lastName;"), recovered);
    }

    @Test
    void aDiffThatDoesNotApplyIsRefusedRatherThanApproximated() throws Exception {
        Path root = repo(BASELINE.replace("lastName;", "lastName,\n    email;"));
        String diff = diffOf(root);
        Assertions.assertTrue(diff.contains("lastName"), "the fixture diff must mention the constant");

        // Replace the working tree with text the patch cannot possibly apply to. Editing the diff's
        // context instead would NOT be a reliable negative: git applies with fuzz when a hunk's text is
        // still findable nearby, so an "obviously wrong" context can still produce a baseline — which is
        // the exact failure direction this class exists to prevent.
        Files.writeString(root.resolve("example/PersonSummary_.java"),
                "package example;\npublic enum TotallyDifferent_ {\n    alpha,\n    beta;\n}\n");

        java.io.IOException failure = Assertions.assertThrows(java.io.IOException.class,
                () -> UnifiedDiff.baselineFrom(diff, root));
        Assertions.assertTrue(failure.getMessage().contains("could not be reverse-applied"),
                "the refusal says the diff did not apply: " + failure.getMessage());
    }

    @Test
    void aHunkOutsideTheFileIsRefused() throws Exception {
        Path root = repo(BASELINE.replace("lastName;", "lastName,\n    email;"));
        String diff = diffOf(root);
        // A hunk claiming line numbers far past the end of a five-line file: git cannot place it, and
        // the recovery must fail rather than approximate.
        String impossible = diff.replaceFirst("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@", "@@ -999,7 +999,7 @@");

        java.io.IOException failure = Assertions.assertThrows(java.io.IOException.class,
                () -> UnifiedDiff.baselineFrom(impossible, root));
        Assertions.assertTrue(failure.getMessage().contains("could not be reverse-applied"),
                failure.getMessage());
    }

    @Test
    void aDiffWithNoFileHeadersIsRefused() throws Exception {
        Path root = repo(BASELINE);

        java.io.IOException failure = Assertions.assertThrows(java.io.IOException.class,
                () -> UnifiedDiff.baselineFrom("not a diff at all\n", root));

        // git refuses it first ("No valid patches in input"), which is the right answer: a baseline
        // recovered from something that is not a diff would be a guess.
        Assertions.assertTrue(failure.getMessage().contains("could not be reverse-applied"),
                failure.getMessage());
    }

    @Test
    void theWorkingTreeIsNotModifiedByRecoveringABaseline() throws Exception {
        Path root = repo(BASELINE.replace("lastName;", "lastName,\n    email;"));
        String before = Files.readString(root.resolve("example/PersonSummary_.java"));

        UnifiedDiff.baselineFrom(diffOf(root), root);

        Assertions.assertEquals(before, Files.readString(root.resolve("example/PersonSummary_.java")),
                "the caller asked a question about its tree; the answer must not edit it");
    }
}
