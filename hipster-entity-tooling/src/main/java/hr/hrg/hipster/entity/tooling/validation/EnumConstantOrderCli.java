package hr.hrg.hipster.entity.tooling.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CLI for the R1 order checker (plan.dsflash § 4.6/R1.3, task 1.16).
 *
 * <p>Usage:</p>
 * <pre>
 *   EnumConstantOrderCli --repo &lt;path&gt; (--baseline &lt;ref&gt; | --diff &lt;file&gt;) [--target &lt;ref&gt;] [--strict]
 * </pre>
 *
 * <p>It exits <strong>non-zero on any violation</strong>, so it can gate a build and a PR check.
 * Warnings (an {@code allowReorder} escape hatch being present) go to stderr without failing unless
 * {@code --strict} is given.</p>
 *
 * <h3>Baseline sources</h3>
 * <p>Two, and exactly one of them per run:</p>
 * <ul>
 *   <li><strong>a git ref</strong> ({@code --baseline HEAD}, a commit, or a PR base such as
 *       {@code origin/main}), resolved by shelling out to {@code git show <ref>:<path>}. This is the
 *       primary form.</li>
 *   <li><strong>a unified diff file</strong> ({@code --diff <file>}) — what a PR check actually has
 *       when the branch is not fetched locally. The diff is reverse-applied to the working tree to
 *       recover the pre-change text ({@link UnifiedDiff}); a diff that cannot be applied exactly is a
 *       usage error, never a silently mis-read baseline.</li>
 * </ul>
 * <p>It is never "the previous run": the baseline must be reproducible from the repository, which is
 * what makes the comparison meaningful across machines.</p>
 */
public final class EnumConstantOrderCli {

    private EnumConstantOrderCli() {
    }

    /** The process exit code contract: 0 clean, 1 violation, 2 usage/environment error. */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Testable entry point; returns the exit code instead of exiting, with the diagnostics on
     * {@code System.err}.
     */
    public static int run(String[] args) {
        return run(args, System.err);
    }

    /**
     * As {@link #run(String[])}, with the diagnostics directed at {@code err}.
     *
     * <p>The stream is a parameter so a test can assert <em>why</em> an exit code was 2, not only that
     * it was: "the baseline is unusable" and "the baseline applied but named nothing" are different
     * outcomes with the same status, and a caller reading the status alone cannot tell them apart.</p>
     */
    public static int run(String[] args, java.io.PrintStream err) {
        Map<String, String> options = parseOptions(args);

        Path repo = options.containsKey("repo") ? Path.of(options.get("repo")) : Path.of(".");
        String baselineRef = options.get("baseline");
        String targetRef = options.getOrDefault("target", "WORKING_TREE");
        boolean strict = options.containsKey("strict");
        String diffFile = options.get("diff");

        if (baselineRef == null && diffFile == null) {
            err.println("enum-order: a baseline is required: --baseline <git-ref> or "
                    + "--diff <unified-diff-file>");
            return 2;
        }
        if (baselineRef != null && diffFile != null) {
            err.println("enum-order: --baseline and --diff are mutually exclusive: they are "
                    + "two ways to name the same thing (the pre-change state), and accepting both "
                    + "would mean silently picking one");
            return 2;
        }

        Map<String, String> baselineSources;
        try {
            if (diffFile != null) {
                // A diff names the files it touches relative to the tree it applies to, so this form
                // is read against --repo (default "."). The paths come from the `+++ b/...` headers.
                // CRLF is normalised because a patch produced on Windows — or one that picked up CRLF
                // in transit — otherwise carries `\r` into every body line and fails to apply.
                baselineSources = UnifiedDiff.baselineFrom(
                        Files.readString(Path.of(diffFile)).replace("\r\n", "\n").replace("\r", "\n"), repo);
            } else {
                baselineSources = readRevision(repo, baselineRef);
            }
        } catch (IOException | InterruptedException e) {
            String source = diffFile != null ? "--diff '" + diffFile + "'" : "--baseline '" + baselineRef + "'";
            err.println("enum-order: cannot read " + source + ": " + e.getMessage());
            return 2;
        }

        Map<String, String> targetSources;
        try {
            targetSources = "WORKING_TREE".equals(targetRef)
                    ? readWorkingTree(repo)
                    : readRevision(repo, targetRef);
        } catch (IOException | InterruptedException e) {
            err.println("enum-order: cannot read target '" + targetRef + "': " + e.getMessage());
            return 2;
        }

        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Map.Entry<String, String> entry : baselineSources.entrySet()) {
            String targetSource = targetSources.get(entry.getKey());
            if (targetSource == null) {
                continue; // the file was deleted; another tool owns that decision
            }
            for (Map.Entry<String, EnumConstantOrderChecker.EnumLedger> ledger
                    : EnumConstantOrderChecker.readLedgers(entry.getValue()).entrySet()) {
                EnumConstantOrderChecker.EnumLedger before = ledger.getValue();
                if (!before.guarded()) {
                    continue; // opt-in by absence: an unmarked baseline enum is not a ledger yet
                }
                if (before.allowReorder()) {
                    warnings.add("enum_reorder_allowed: " + before.qualifiedName() + " in " + entry.getKey());
                }
                EnumConstantOrderChecker.EnumLedger after = EnumConstantOrderChecker
                        .readLedgers(targetSource).get(ledger.getKey());
                if (after == null) {
                    violations.add(entry.getKey() + " " + ledger.getKey()
                            + " enum_constant_removed: the whole enum disappeared");
                    continue;
                }
                for (String violation : EnumConstantOrderChecker
                        .compare(before.constants(), after.constants()).violations()) {
                    violations.add(entry.getKey() + " " + ledger.getKey() + " " + violation);
                }
            }
        }

        for (String warning : warnings) {
            err.println("enum-order: WARNING " + warning);
        }
        for (String violation : violations) {
            err.println("enum-order: kind=enum_order, location=" + violation
                    + ", action=fail: keep the constant and append new fields at the end (R1)");
        }

        boolean failed = !violations.isEmpty() || (strict && !warnings.isEmpty());
        System.out.println("enum-order: checked " + baselineSources.size() + " file(s) against '"
                + (diffFile != null ? diffFile : baselineRef) + "'; " + violations.size()
                + " violation(s), " + warnings.size() + " warning(s)");
        return failed ? 1 : 0;
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq >= 0) {
                options.put(key.substring(0, eq), key.substring(eq + 1));
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.put(key, args[++i]);
            } else {
                options.put(key, "true");
            }
        }
        return options;
    }

    /** All tracked {@code .java} files at a git ref, keyed by repo-relative path. */
    private static Map<String, String> readRevision(Path repo, String ref)
            throws IOException, InterruptedException {
        List<String> files = git(repo, "ls-tree", "-r", "--name-only", ref);
        Map<String, String> sources = new LinkedHashMap<>();
        for (String file : files) {
            if (!file.endsWith(".java")) {
                continue;
            }
            List<String> lines = git(repo, "show", ref + ":" + file);
            sources.put(file, String.join("\n", lines));
        }
        return sources;
    }

    /** All {@code .java} files in the working tree, keyed by repo-relative path. */
    private static Map<String, String> readWorkingTree(Path repo) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        try (var walk = Files.walk(repo)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                // Never descend into another checkout: .kilo/worktrees holds a full copy of this
                // tree (plan.dsflash § 4.1/S2, GR-6), and reading it would compare a tree to itself.
                // `.jcodebuddy` is tool output (metadata reports), never a source of enums.
                String relative = repo.relativize(file).toString().replace('\\', '/');
                if (relative.startsWith(".kilo/worktrees/") || relative.contains("/target/")
                        || relative.contains(".jcodebuddy/")) {
                    continue;
                }
                sources.put(relative, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    private static List<String> git(Path repo, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toAbsolutePath().toString());
        command.addAll(List.of(args));

        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        List<String> lines;
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().collect(Collectors.toList());
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("git " + String.join(" ", args) + " exited " + exit);
        }
        return lines;
    }
}
