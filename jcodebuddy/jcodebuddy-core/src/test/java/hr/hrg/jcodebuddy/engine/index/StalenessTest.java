package hr.hrg.jcodebuddy.engine.index;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

/**
 * {@link Staleness} — the one answer to "is this item stale, and why?", which the on-demand command and a
 * watch loop both read (plan step 6.6).
 *
 * <p>The four verdicts are asserted one by one, because a boolean would have merged the two that matter
 * most: a file with <strong>no entry</strong> is not stale (nothing was ever recorded, so a fresh checkout
 * would look entirely stale and a rebuild would be triggered for the whole tree) and a <strong>touched</strong>
 * file is reusable (the maintainer's own case: *"a touch is not a change"*). The tier is asserted with the
 * verdict, because "which tier decided" is the question a person asks next — and a verdict that never reports
 * {@link Staleness.Tier#STAT} would mean the cheap tier is not running.</p>
 */
class StalenessTest {

    private static final String SOURCE = "package t;\npublic interface A {}\n";

    /** A tree with one source file and one stored entry for it, through the pass's own two halves. */
    private static Path storedEntry(Path moduleRoot, String relative, String text) throws IOException {
        Path file = moduleRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);

        MetadataCache cache = MetadataCache.beside(moduleRoot.resolve(".jcodebuddy"));
        var unit = hr.hrg.jcodebuddy.engine.source.SourceReader.readSourceText(text);
        Assertions.assertNotNull(unit, "the fixture must parse");
        cache.store(FileMetadata.of(relative, ContentHash.of(file), Files.size(file),
                Files.getLastModifiedTime(file).toMillis(), false,
                ClassIndex.factsOf(unit, text),
                hr.hrg.jcodebuddy.engine.source.TreeQueries.importLines(unit)), null);
        return file;
    }

    private static Staleness gate(Path tree) {
        return Staleness.beside(tree.resolve(".jcodebuddy"));
    }

    @Test
    void aFileWithNoEntryIsUnknownAndNotStale(@TempDir Path tree) throws IOException {
        Path file = tree.resolve("src/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);

        Staleness.Result result = gate(tree).check("src/A.java", file);

        Assertions.assertEquals(Staleness.Verdict.UNKNOWN, result.verdict());
        Assertions.assertEquals(Staleness.Tier.NONE, result.tier());
        Assertions.assertFalse(result.reusable(), "nothing can be reused: nothing was ever recorded");
        Assertions.assertTrue(result.rebuildNeeded(),
                "a first pass must build it — but as UNKNOWN, so a caller can tell a fresh checkout from a "
                        + "tree whose files really changed");
        Assertions.assertTrue(result.cause().contains("no base entry"), result.cause());
    }

    @Test
    void anUnchangedFileIsUnchangedByTheStatAlone(@TempDir Path tree) throws IOException {
        Path file = storedEntry(tree, "A.java", SOURCE);

        Staleness.Result result = gate(tree).check("A.java", file);

        Assertions.assertEquals(Staleness.Verdict.UNCHANGED, result.verdict());
        Assertions.assertEquals(Staleness.Tier.STAT, result.tier(),
                "the cheap tier decided it, so the source was neither read nor hashed");
        Assertions.assertTrue(result.reusable());
        Assertions.assertFalse(result.rebuildNeeded());
    }

    @Test
    void aTouchedFileIsReusableAndTakesTheHashTier(@TempDir Path tree) throws IOException {
        Path file = storedEntry(tree, "A.java", SOURCE);

        // Identical bytes, a different modification time: the maintainer's "a touch is not a change".
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(120)));

        Staleness.Result result = gate(tree).check("A.java", file);

        Assertions.assertEquals(Staleness.Verdict.TOUCHED, result.verdict());
        Assertions.assertEquals(Staleness.Tier.HASH, result.tier(), "the stat moved, so the hash had to decide");
        Assertions.assertTrue(result.reusable(), "a touch must not force a rebuild");
        Assertions.assertFalse(result.rebuildNeeded());
    }

    @Test
    void anEditedFileIsStale(@TempDir Path tree) throws IOException {
        Path file = storedEntry(tree, "A.java", SOURCE);

        Files.writeString(file, SOURCE.replace("interface A", "interface B"), StandardCharsets.UTF_8);

        Staleness.Result result = gate(tree).check("A.java", file);

        Assertions.assertEquals(Staleness.Verdict.STALE, result.verdict());
        Assertions.assertEquals(Staleness.Tier.HASH, result.tier());
        Assertions.assertFalse(result.reusable());
        Assertions.assertTrue(result.rebuildNeeded());
    }

    @Test
    void theGateNeverWrites(@TempDir Path tree) throws IOException {
        Path file = storedEntry(tree, "A.java", SOURCE);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(120)));
        Path entry = tree.resolve(".jcodebuddy/cache/A.java.json");
        String before = Files.readString(entry, StandardCharsets.UTF_8);

        gate(tree).check("A.java", file);

        Assertions.assertEquals(before, Files.readString(entry, StandardCharsets.UTF_8),
                "the verdict is read-only: the self-healing refresh belongs to the pass (MetadataCache), "
                        + "not to a component a watch loop and a CLI command both call");
    }

    @Test
    void ascanReportsEveryFilesVerdictInPathOrder(@TempDir Path tree) throws IOException {
        // One module root, two files, keyed exactly as the pass keys them: relative to the MODULE.
        storedEntry(tree, "src/main/java/a/One.java", SOURCE);
        storedEntry(tree, "src/main/java/b/Two.java", SOURCE);
        Files.writeString(tree.resolve("src/main/java/b/Two.java"),
                SOURCE.replace("interface A", "interface C"), StandardCharsets.UTF_8);

        List<Staleness.Result> results = gate(tree).scan(tree.resolve("src/main/java"), tree, null);

        Assertions.assertEquals(List.of("src/main/java/a/One.java", "src/main/java/b/Two.java"),
                results.stream().map(Staleness.Result::path).toList(),
                "every .java file under the root, in path order — and keyed the way the pass stores it");
        Assertions.assertEquals(Staleness.Verdict.UNCHANGED, results.get(0).verdict());
        Assertions.assertEquals(Staleness.Verdict.STALE, results.get(1).verdict(),
                "the two files must not share a verdict: the report is per file");
    }

    @Test
    void aScanOfAMissingRootIsEmptyRatherThanAFailure(@TempDir Path tree) throws IOException {
        Assertions.assertTrue(gate(tree).scan(tree.resolve("src/main/java"), tree, null).isEmpty(),
                "a module that was never generated has no source root to sweep, and that is not an error");
    }
}
