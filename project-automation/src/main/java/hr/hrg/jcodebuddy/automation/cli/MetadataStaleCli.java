// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.automation.cli;

import hr.hrg.jcodebuddy.engine.index.Staleness;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The on-demand entry of plan step 6.6: {@code jcodebuddy metadata stale <path>}.
 *
 * <p>It answers the one question a tool asks before asking for metadata — <em>is this stale, and why?</em> —
 * through {@link Staleness}, which is the same implementation a watch loop reads. That is the point of the step:
 * the two entries must not be able to disagree about what "stale" means, and the way they would is a second
 * copy of the tiered ladder (stat, then hash) drifting from this one.</p>
 *
 * <h3>Two shapes, one gate</h3>
 * <ul>
 *   <li><strong>A file</strong> — one JSON object with the verdict, the tier that decided it and the cause.
 *       Nothing is written: no entry is created, refreshed or repaired, because a check that wrote would make
 *       this command change the thing it reports on.</li>
 *   <li><strong>A directory</strong> — one JSON object summarising the sweep, plus a line per item that needs a
 *       rebuild. {@code --cached-only} narrows the summary to items with an entry, which is the question "what
 *       did I change?"; the default also counts the files nothing is known about, which is the question "is this
 *       checkout warm?".</li>
 * </ul>
 *
 * <h3>Exit codes are the contract</h3>
 * <p>{@code 0} when nothing needs a rebuild, {@code 1} when something does, {@code 2} for a usage error or an
 * unreadable path. That makes the command usable in a shell condition without parsing its output, and it keeps
 * the same split {@link MetadataCli} uses for its own two failures: bad input is 2, a real answer is 0 or 1.</p>
 */
public final class MetadataStaleCli {

    /** The command's own name, as the user types it. */
    public static final String COMMAND = "metadata stale";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataStaleCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The command, with its streams and exit code as parameters so a test drives exactly what a user runs.
     *
     * @return {@code 0} nothing stale, {@code 1} something stale, {@code 2} a usage error or unreadable path
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 3 || args.length > 4
                || !"metadata".equals(args[0]) || !"stale".equals(args[1])) {
            err.println("usage: jcodebuddy " + COMMAND + " <file|directory> [--cached-only]");
            return 2;
        }
        boolean cachedOnly = args.length == 4;
        if (cachedOnly && !"--cached-only".equals(args[3])) {
            err.println("unknown option '" + args[3] + "'");
            err.println("usage: jcodebuddy " + COMMAND + " <file|directory> [--cached-only]");
            return 2;
        }
        Path target = Paths.get(args[2]);
        if (Files.isDirectory(target)) {
            return sweep(target, cachedOnly, out, err);
        }
        if (!Files.isRegularFile(target)) {
            err.println("cannot read " + target.toAbsolutePath().normalize());
            return 2;
        }
        return oneFile(target, out, err);
    }

    /** The single-file shape: the verdict as JSON, exit 0 when it needs no rebuild and 1 when it does. */
    private static int oneFile(Path file, PrintStream out, PrintStream err) {
        Path moduleRoot = moduleRootOf(file);
        try {
            Staleness.Result result = Staleness.beside(moduleRoot.resolve(".jcodebuddy"))
                    .check(relative(moduleRoot, file), file);
            out.println(MAPPER.writeValueAsString(describe(result)));
            return result.rebuildNeeded() ? 1 : 0;
        } catch (RuntimeException failure) {
            err.println("cannot judge " + file.toAbsolutePath().normalize() + ": " + failure.getMessage());
            return 2;
        }
    }

    /** The directory shape: the sweep's summary, then one line per item that needs a rebuild. */
    private static int sweep(Path directory, boolean cachedOnly, PrintStream out, PrintStream err) {
        Path root = directory.toAbsolutePath().normalize();
        Path moduleRoot = moduleRootOf(root);
        try {
            List<Staleness.Result> results = Staleness.beside(moduleRoot.resolve(".jcodebuddy"))
                    .scan(root, moduleRoot, null);
            List<Staleness.Result> reported = cachedOnly
                    ? results.stream().filter(result -> result.verdict() != Staleness.Verdict.UNKNOWN).toList()
                    : results;
            List<Staleness.Result> rebuild = reported.stream().filter(Staleness.Result::rebuildNeeded).toList();

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("root", moduleRoot.relativize(root).toString().replace('\\', '/'));
            summary.put("moduleRoot", moduleRoot.toString());
            summary.put("scanned", reported.size());
            summary.put("cachedOnly", cachedOnly);
            for (Staleness.Verdict verdict : Staleness.Verdict.values()) {
                // Every verdict is written, including the zeroes: a report a reader has to interpret by
                // absence is how "no entry" comes to look like "unchanged".
                summary.put(verdict.name().toLowerCase(java.util.Locale.ROOT) + "Count",
                        reported.stream().filter(result -> result.verdict() == verdict).count());
            }
            summary.put("rebuildNeeded", rebuild.size());
            out.println(MAPPER.writeValueAsString(summary));
            for (Staleness.Result result : rebuild) {
                out.println(result.verdict().name().toLowerCase(java.util.Locale.ROOT)
                        + " " + result.tier().name().toLowerCase(java.util.Locale.ROOT)
                        + " " + result.path() + " (" + result.cause() + ")");
            }
            return rebuild.isEmpty() ? 0 : 1;
        } catch (IOException failure) {
            err.println("cannot sweep " + root + ": " + failure.getMessage());
            return 2;
        } catch (RuntimeException failure) {
            err.println("cannot sweep " + root + ": " + failure.getMessage());
            return 2;
        }
    }

    /** One verdict as a JSON-ready map, in a fixed key order. */
    private static Map<String, Object> describe(Staleness.Result result) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("path", result.path());
        document.put("verdict", result.verdict().name());
        document.put("tier", result.tier().name());
        document.put("cause", result.cause());
        document.put("reusable", result.reusable());
        document.put("rebuildNeeded", result.rebuildNeeded());
        return document;
    }

    /**
     * The module root a path belongs to, so the command's keys are the pass's keys.
     *
     * <p>The walk up is <strong>not</strong> "the nearest ancestor with a {@code .jcodebuddy/} directory", and
     * that distinction is the whole of this method. A nested {@code .jcodebuddy/} does occur in the wild — the
     * example's own {@code .jcodebuddy/agent-state/markdown-view/} carries one, and so does a scratch probe left
     * inside a source package — so the nearest one is often a directory that no pass ever keyed an entry
     * against. Resolving to it would make every key a bare file name, every file would look {@code UNKNOWN}, and
     * the command would be silently useless on exactly the checkout it exists for.</p>
 *
     * <p>A directory is the module root when it carries the derived subtree the pass writes beside a cache — the
     * class index, which every indexed module here has — or a cache itself. Within that filter, several are
     * possible and the <strong>highest</strong> wins: a distinct module always has a distinct root, and of two
     * ancestors the outer one is the module. The nearest such directory is the fallback for a module whose
     * derived output was deleted, and the path's own directory is the last resort: nothing can then answer, and
     * every file must read {@link Staleness.Verdict#UNKNOWN} rather than be given a fabricated verdict.</p>
     */
    static Path moduleRootOf(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path start = Files.isDirectory(absolute) ? absolute : absolute.getParent();
        if (start == null) {
            return absolute;
        }
        Path highest = null;
        Path nearest = null;
        for (Path current = start; current != null; current = current.getParent()) {
            if (!isModuleRoot(current)) {
                continue;
            }
            if (highest == null) {
                highest = current;
            }
            nearest = current;
        }
        if (highest != null) {
            return highest;
        }
        return start;
    }

    /** Whether a directory carries the marker or the derived output a pass writes beside it. */
    private static boolean isModuleRoot(Path directory) {
        return Files.isDirectory(directory.resolve(".jcodebuddy"))
                && (Files.isDirectory(directory.resolve(".jcodebuddy").resolve("metadata"))
                || Files.isDirectory(directory.resolve(".jcodebuddy").resolve("index"))
                || Files.isDirectory(directory.resolve(".jcodebuddy").resolve("cache"))
                || Files.isRegularFile(directory.resolve("pom.xml")));
    }

    /** The key the entry is stored under, resolved the same way the pass resolves it. */
    static String relative(Path moduleRoot, Path file) {
        return Staleness.moduleRelativePath(moduleRoot, file);
    }
}
