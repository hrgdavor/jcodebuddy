// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.automation.cli;

import hr.hrg.jcodebuddy.meta.IndexMetadataProvider;
import hr.hrg.jcodebuddy.meta.MetadataProvider;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The manual-mode CLI DEC-W008 names: {@code jcodebuddy metadata parse <file>}.
 *
 * <p>It invokes the provider's <b>no-cache path directly</b> — {@link IndexMetadataProvider#parseSource} — and prints
 * the resulting {@link MetadataProvider.CacheEntry} as JSON on stdout. That is the whole point of the decision: a
 * developer, a script or an agent can ask what a single file's metadata is <b>in a fresh checkout</b>, with no daemon
 * running, no cache folder and no prior {@code scan}. Nothing here reads or writes {@code .jcodebuddy/}.
 *
 * <p><b>The JSON is the RPC's own answer, not a look-alike.</b> The object printed is the same {@code CacheEntry} that
 * {@code MetadataRpcService.parseFile} returns for the same file, serialized by the same Jackson mapper — so a caller
 * that parses either form gets the same model, which is what the step's round-trip requirement means. A second, CLI-only
 * shape would drift from the RPC the first time either gained a field.
 *
 * <p>Exit codes are the command's contract: {@code 0} when the entry was produced, {@code 2} for a usage error and for a
 * file that cannot be read. A failure prints to stderr and prints <b>nothing</b> on stdout, so a caller that pipes the
 * output never mistakes an error message for metadata.
 */
public final class MetadataCli {

    /** The command's own name, as the user types it. */
    public static final String COMMAND = "metadata parse";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The command, with its streams and exit code as parameters so a test drives exactly what a user runs.
     *
     * @return {@code 0} on success, {@code 2} on a usage error or an unreadable file
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 3 || !"metadata".equals(args[0]) || !"parse".equals(args[1])) {
            err.println("usage: jcodebuddy " + COMMAND + " <file>");
            return 2;
        }
        Path file = Paths.get(args[2]);
        if (!Files.isReadable(file)) {
            err.println("cannot read " + file.toAbsolutePath().normalize());
            return 2;
        }
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            err.println("cannot read " + file.toAbsolutePath().normalize() + ": " + failure.getMessage());
            return 2;
        }
        try {
            MetadataProvider.CacheEntry entry =
                    IndexMetadataProvider.parseSource(relativePathOf(file), source.getBytes(StandardCharsets.UTF_8));
            out.println(MAPPER.writeValueAsString(entry));
            return 0;
        } catch (RuntimeException failure) {
            err.println("cannot parse " + file.toAbsolutePath().normalize() + ": " + failure.getMessage());
            return 2;
        }
    }

    /**
     * The path the metadata is keyed by: relative to the working directory, with forward slashes.
     *
     * <p>Forward slashes because that is what every stored path in this repository uses (the class index, the report
     * links and the RPC's own {@code relPath} parameter), and a key with a backslash would name the same file
     * differently on Windows than on the machines that produced the cache. A file outside the working directory — a
     * different drive on Windows, say — falls back to its own name rather than failing: the metadata of one file needs
     * no project-relative key to be useful.
     */
    static String relativePathOf(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        try {
            Path cwd = Paths.get("").toAbsolutePath().normalize();
            return cwd.relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException outsideWorkingDirectory) {
            return absolute.getFileName().toString();
        }
    }
}
