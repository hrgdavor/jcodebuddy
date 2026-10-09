// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.jcodebuddy.automation.cli;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.index.ContentHash;
import hr.hrg.jcodebuddy.engine.index.FileMetadata;
import hr.hrg.jcodebuddy.engine.index.MetadataCache;
import hr.hrg.jcodebuddy.engine.index.Staleness;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The on-demand entry of plan step 6.6: {@code jcodebuddy metadata stale <path>}.
 *
 * <p>What this test is really about is that the command and a pass <strong>agree</strong>: the entries it reads are
 * the ones {@link MetadataCache} writes, keyed the way {@code MetadataCache} keys them, judged by
 * {@link Staleness} — the same component a watch loop reads. So each case below stores an entry through the real
 * write path and then asks the command, rather than hand-building a cache file that only the test understands.</p>
 *
 * <p>The exit code is asserted with the output because it is what a shell uses: {@code 0} nothing to rebuild,
 * {@code 1} something to rebuild, {@code 2} a usage error or an unreadable path. A command whose exit code was
 * always {@code 0} would be unusable in a condition no matter how good its JSON is.</p>
 */
class MetadataStaleCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = """
            package t;

            public interface A {
            }
            """;

    private record Run(int status, String out, String err) {
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            status = MetadataStaleCli.run(args, outStream, errStream);
        }
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Stores an entry for one file through the pass's own write path, so the command is judged against a real
     * cache rather than against a fixture invented here.
     */
    private static void store(Path moduleRoot, String relative, String text) throws IOException {
        Path file = moduleRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);

        var unit = hr.hrg.jcodebuddy.engine.source.SourceReader.readSourceText(text);
        Assertions.assertNotNull(unit, "the fixture must parse");
        MetadataCache cache = MetadataCache.beside(moduleRoot.resolve(".jcodebuddy"));
        cache.store(FileMetadata.of(relative, ContentHash.of(file), Files.size(file),
                Files.getLastModifiedTime(file).toMillis(), false,
                ClassIndex.factsOf(unit, text),
                hr.hrg.jcodebuddy.engine.source.TreeQueries.importLines(unit)), null);
    }

    @Test
    void anUnchangedFileIsReportedFreshAndExitsZero(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);

        Run run = run("metadata", "stale", module.resolve("src/main/java/A.java").toString());

        Assertions.assertEquals(0, run.status(), run.err() + run.out());
        JsonNode printed = MAPPER.readTree(run.out());
        Assertions.assertEquals("UNCHANGED", printed.path("verdict").asText(), run.out());
        Assertions.assertEquals("STAT", printed.path("tier").asText(),
                "the cheap tier must be the one that decided, or the gate is not running");
        Assertions.assertFalse(printed.path("rebuildNeeded").asBoolean());
    }

    @Test
    void anEditedFileIsReportedStaleAndExitsOne(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        Path file = module.resolve("src/main/java/A.java");
        Files.writeString(file, SOURCE.replace("interface A", "interface B"), StandardCharsets.UTF_8);

        Run run = run("metadata", "stale", file.toString());

        Assertions.assertEquals(1, run.status(), "a caller must be able to branch on this without parsing JSON");
        JsonNode printed = MAPPER.readTree(run.out());
        Assertions.assertEquals("STALE", printed.path("verdict").asText(), run.out());
        Assertions.assertEquals("HASH", printed.path("tier").asText());
        Assertions.assertTrue(printed.path("rebuildNeeded").asBoolean());
    }

    @Test
    void aFileNothingIsKnownAboutIsUnknownRatherThanStale(@TempDir Path module) throws Exception {
        Path file = module.resolve("src/main/java/New.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);

        Run run = run("metadata", "stale", file.toString());

        Assertions.assertEquals(1, run.status(), "it does need building");
        JsonNode printed = MAPPER.readTree(run.out());
        Assertions.assertEquals("UNKNOWN", printed.path("verdict").asText(),
                "but as UNKNOWN: a fresh checkout must not be reported as a tree whose files changed");
        Assertions.assertEquals("NONE", printed.path("tier").asText());
    }

    @Test
    void theSweepSummarisesEveryVerdictAndNarrowsToCachedItemsOnRequest(@TempDir Path module) throws Exception {
        store(module, "src/main/java/One.java", SOURCE);
        store(module, "src/main/java/Two.java", SOURCE);
        Files.writeString(module.resolve("src/main/java/Two.java"),
                SOURCE.replace("interface A", "interface C"), StandardCharsets.UTF_8);
        Files.writeString(module.resolve("src/main/java/Brand.java"), SOURCE, StandardCharsets.UTF_8);

        Run all = run("metadata", "stale", module.resolve("src/main/java").toString());
        Assertions.assertEquals(1, all.status(), all.err());
        JsonNode summary = MAPPER.readTree(all.out().lines().findFirst().orElseThrow());
        Assertions.assertEquals(3, summary.path("scanned").asInt(), all.out());
        Assertions.assertEquals(1, summary.path("unchangedCount").asInt(), all.out());
        Assertions.assertEquals(1, summary.path("staleCount").asInt(), all.out());
        Assertions.assertEquals(1, summary.path("unknownCount").asInt(), all.out());
        Assertions.assertEquals(2, summary.path("rebuildNeeded").asInt(), all.out());
        Assertions.assertTrue(all.out().contains("stale hash src/main/java/Two.java"), all.out());

        Run cachedOnly = run("metadata", "stale", module.resolve("src/main/java").toString(), "--cached-only");
        JsonNode narrowed = MAPPER.readTree(cachedOnly.out().lines().findFirst().orElseThrow());
        Assertions.assertEquals(2, narrowed.path("scanned").asInt(),
                "--cached-only drops the files nothing is known about: 'what did I change?'");
        Assertions.assertEquals(0, narrowed.path("unknownCount").asInt(), cachedOnly.out());
        Assertions.assertEquals(1, narrowed.path("staleCount").asInt(), cachedOnly.out());
    }

    @Test
    void nothingToRebuildExitsZero(@TempDir Path module) throws Exception {
        store(module, "src/main/java/One.java", SOURCE);

        Run run = run("metadata", "stale", module.resolve("src/main/java").toString());

        Assertions.assertEquals(0, run.status(), "a warm tree is a success, so a script can use it as a check");
        JsonNode summary = MAPPER.readTree(run.out().lines().findFirst().orElseThrow());
        Assertions.assertEquals(0, summary.path("rebuildNeeded").asInt(), run.out());
    }

    @Test
    void theCommandWritesNothing(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        Path entry = module.resolve(".jcodebuddy/cache/src/main/java/A.java.json");
        String before = Files.readString(entry, StandardCharsets.UTF_8);

        run("metadata", "stale", module.resolve("src/main/java/A.java").toString());

        Assertions.assertEquals(before, Files.readString(entry, StandardCharsets.UTF_8),
                "a check that wrote would change the thing it reports on");
    }

    @Test
    void aUsageErrorOrAnUnreadablePathExitsTwoWithNothingOnStdout(@TempDir Path module) {
        Run usage = run("metadata", "stale");
        Assertions.assertEquals(2, usage.status());
        Assertions.assertTrue(usage.err().contains("usage: jcodebuddy " + MetadataStaleCli.COMMAND), usage.err());
        Assertions.assertEquals("", usage.out(), "an error must never be mistakable for a verdict");

        Run missing = run("metadata", "stale", module.resolve("NotThere.java").toString());
        Assertions.assertEquals(2, missing.status());
        Assertions.assertTrue(missing.err().contains("cannot read"), missing.err());
        Assertions.assertEquals("", missing.out());

        Run option = run("metadata", "stale", module.toString(), "--wrong");
        Assertions.assertEquals(2, option.status());
        Assertions.assertTrue(option.err().contains("unknown option"), option.err());
    }

    @Test
    void theModuleRootIsFoundSoRelativeKeysMatchThePass(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        // A module's derived subtree is what identifies its root; the entry's own directory does not, which is
        // the case the "nearest .jcodebuddy" rule got wrong.
        Files.createDirectories(module.resolve(".jcodebuddy/metadata"));

        Assertions.assertEquals(module.toAbsolutePath().normalize(),
                MetadataStaleCli.moduleRootOf(module.resolve("src/main/java/A.java")),
                "the module root is found from a path below it");
        Assertions.assertEquals("src/main/java/A.java",
                MetadataStaleCli.relative(module, module.resolve("src/main/java/A.java")));
    }

    @Test
    void aStrayNestedJcodebuddyInASourcePackageDoesNotBecomeTheModuleRoot(@TempDir Path module) throws Exception {
        store(module, "src/main/java/A.java", SOURCE);
        Files.createDirectories(module.resolve(".jcodebuddy/metadata"));
        // A scratch probe inside the package, carrying its own `.jcodebuddy/` — observed in this repository, and
        // the case that made the nearest-marker rule report a warm file as UNKNOWN under a bare file-name key.
        Files.createDirectories(module.resolve("src/main/java/.jcodebuddy/agent-state"));

        Assertions.assertEquals(module.toAbsolutePath().normalize(),
                MetadataStaleCli.moduleRootOf(module.resolve("src/main/java/A.java")),
                "the stray marker must not win: the module is the directory with the derived subtree");

        Run run = run("metadata", "stale", module.resolve("src/main/java/A.java").toString());
        JsonNode printed = MAPPER.readTree(run.out());
        Assertions.assertEquals("src/main/java/A.java", printed.path("path").asText(),
                "so the key is the one the pass stored, and a warm file reads UNCHANGED");
        Assertions.assertEquals("UNCHANGED", printed.path("verdict").asText(), run.out());
    }
}
