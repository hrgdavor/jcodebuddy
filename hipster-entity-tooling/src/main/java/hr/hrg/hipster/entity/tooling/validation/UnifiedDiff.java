package hr.hrg.hipster.entity.tooling.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs the <strong>baseline revision</strong> of the files a unified diff touches
 * (plan.dsflash § 4.6/R1.3, task 1.16).
 *
 * <h3>Why a diff is a legitimate baseline</h3>
 * <p>The R1 rule is that the checker's "before" picture must be reproducible from the repository and
 * never "the state of the last run". A git ref is the primary form; a unified diff is the second one,
 * and it is what a PR check actually has when the branch is not fetched locally: the diff <em>is</em>
 * the change under review. Reversing it recovers the pre-change text, which is exactly the baseline
 * the comparison needs.</p>
 *
 * <h3>Why this shells out to {@code git apply} instead of parsing the diff</h3>
 * <p>A hand-written unified-diff applier was implemented first and withdrawn. Per the follow-up plan's
 * own stop rule for this item ("if the unified-diff applier needs more than ~80 lines, delete it"),
 * it had grown past that and had been wrong three times in three different ways, each caught only by
 * its own tests:</p>
 * <ul>
 *   <li>it read the hunk body to a fixed line count and got the arithmetic wrong — a context line
 *       belongs to <em>both</em> sides, so {@code oldCount + newCount} over-counts;</li>
 *   <li>it treated a zero-length body line as noise, but unified diff spells "an empty context line"
 *       exactly that way, so a file with an empty line in context lost an expected line;</li>
 *   <li>and it could not tell a file's terminating newline from a real empty last line, so it dropped
 *       the wrong one and shifted every line of the file.</li>
 * </ul>
 * <p>Each of those is a silent-mis-application risk in the one tool whose entire purpose is to detect
 * a silently mis-applied migration. {@code git apply -R} implements the format, is exercised by every
 * git user, and refuses anything it cannot apply exactly — so the failure direction is the one this
 * class wants. The diff is written to a temporary file rather than piped, because a diff on stdin
 * cannot be re-read for a diagnostic (and because a confined host may forbid piped stdio).</p>
 *
 * <h3>What it refuses</h3>
 * <p>A diff that does not apply to {@code --repo}'s working tree, or one naming a file that is not
 * there, makes the whole read fail with an {@link IOException}, which the CLI turns into its usage
 * exit code. A baseline that was silently mis-applied is worse than no baseline: the comparison would
 * then report violations against text nobody wrote.</p>
 */
final class UnifiedDiff {

    private UnifiedDiff() {
    }

    /**
     * The baseline text of every file the diff touches, by reverse-applying it to a scratch copy of
     * {@code repoRoot}.
     *
     * <p>The copy is what makes this side-effect free: the reverse application must not touch the
     * working tree it is being asked about, and the scratch directory is created and deleted by this
     * method.</p>
     */
    static Map<String, String> baselineFrom(String diffText, Path repoRoot) throws IOException {
        Path patch = Files.createTempFile("enum-order-baseline", ".patch");
        Path scratch = Files.createTempDirectory("enum-order-scratch");
        try {
            Files.writeString(patch, diffText, StandardCharsets.UTF_8);
            copyTree(repoRoot, scratch);

            List<String> command = new ArrayList<>(List.of("git", "-C", scratch.toAbsolutePath().toString(),
                    "apply", "-R", "-p1", "--unsafe-paths"));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(false)
                    // git apply reads the patch from a file, not from a pipe: a diff that failed to
                    // apply is then re-readable by whoever reads the failure message.
                    .redirectInput(patch.toFile())
                    .start();
            String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                int exit = process.waitFor();
                if (exit != 0) {
                    throw new IOException("the diff could not be reverse-applied to the working tree: "
                            + errors.trim());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while applying the diff", e);
            }

            // The paths the diff named are now the baseline revisions, in place.
            Map<String, String> baseline = new LinkedHashMap<>();
            for (String path : pathsIn(diffText)) {
                Path file = scratch.resolve(path);
                if (Files.exists(file)) {
                    baseline.put(path, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
            if (baseline.isEmpty()) {
                throw new IOException("the diff named no file that could be read back");
            }
            return baseline;
        } finally {
            deleteTree(scratch);
            Files.deleteIfExists(patch);
        }
    }

    /**
     * The {@code +++ b/<path>} targets of the diff.
     *
     * <p>Read from the headers rather than discovered by walking the scratch tree, so the result is the
     * diff's own file set — a file the diff does not touch is not part of the comparison.</p>
     */
    private static List<String> pathsIn(String diffText) {
        List<String> paths = new ArrayList<>();
        for (String line : diffText.replace("\r\n", "\n").split("\n")) {
            if (!line.startsWith("+++ ")) {
                continue;
            }
            String path = line.substring(4).trim();
            int tab = path.indexOf('\t');
            if (tab >= 0) {
                path = path.substring(0, tab);
            }
            if (path.startsWith("b/")) {
                path = path.substring(2);
            }
            // `/dev/null` means the file is new in the working tree, so its baseline is empty; it is
            // simply absent from the map, and the comparison then treats it as an added file.
            if (!path.isBlank() && !path.equals("/dev/null") && !paths.contains(path)) {
                paths.add(path);
            }
        }
        return paths;
    }

    /**
     * Copies the tree without its {@code .git} directory.
     *
     * <p>Skipping {@code .git} is not an optimisation: git's object files are read-only, and copying
     * them produces read-only copies this class would then have to force-delete. The scratch tree only
     * ever needs the working files, because {@code git apply -R} patches text, not history.</p>
     */
    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        try (var walk = Files.walk(from)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path relative = from.relativize(source);
                if (relative.startsWith(".git")) {
                    continue;
                }
                Path target = to.resolve(relative.toString());
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(source, target);
            }
        }
    }

    /**
     * Deletes the scratch tree, tolerating the read-only attribute git leaves on what it writes.
     */
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (java.nio.file.AccessDeniedException e) {
                    // git writes read-only files (its object store, and index files on some platforms);
                    // clear the flag and retry once rather than leaving scratch debris behind.
                    path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
