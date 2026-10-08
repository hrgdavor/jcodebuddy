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
import java.util.ArrayList;
import java.util.List;

/**
 * The staleness gate's tiers — plan step 6.6, and the maintainer's design of 2026-10-08: *"mtime and file size first
 * as easily checkable for FS metadata and then hash as slowest check"*.
 *
 * <p>What makes this test worth having is that the tier is <strong>provable</strong> rather than plausible: the case
 * that must not hash reports {@link MetadataCache#statHits()} equal to its hit count, and the case that must hash
 * while still reusing reports hits with <em>zero</em> stat hits. A test that only asserted "the entry was reused"
 * would pass on the old code, which hashed everything.</p>
 */
class MetadataCacheStalenessTest {

    private static final String SOURCE = "package t;\npublic interface A {}\n";

    /** One pass over {@code file}, through the two halves a real pass uses. */
    private static boolean pass(MetadataCache cache, Path root, String relative, Path file, List<String> problems)
            throws IOException {
        FileMetadata cached = cache.entryFor(relative, file, problems);
        if (cached != null) {
            return true;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        SourceReaderFacts facts = SourceReaderFacts.of(text);
        cache.store(FileMetadata.of(relative, ContentHash.of(file), Files.size(file),
                Files.getLastModifiedTime(file).toMillis(), false, facts.types(), facts.imports()), problems);
        return false;
    }

    /** The parse half, kept to the two things an entry needs. */
    private record SourceReaderFacts(List<TypeFacts> types, List<String> imports) {
        static SourceReaderFacts of(String text) {
            var unit = hr.hrg.jcodebuddy.engine.source.SourceReader.readSourceText(text);
            Assertions.assertNotNull(unit, "the fixture must parse");
            return new SourceReaderFacts(ClassIndex.factsOf(unit, text),
                    hr.hrg.jcodebuddy.engine.source.TreeQueries.importLines(unit));
        }
    }

    @Test
    void anUnchangedFileIsReusedByStatAloneWithoutHashing(@TempDir Path tree) throws IOException {
        MetadataCache cache = MetadataCache.beside(tree.resolve(".jcodebuddy"));
        Path file = tree.resolve("A.java");
        Files.writeString(file, SOURCE);
        List<String> problems = new ArrayList<>();

        Assertions.assertFalse(pass(cache, tree, "A.java", file, problems), "the first pass has no entry to reuse");
        cache.resetCounters();
        Assertions.assertTrue(pass(cache, tree, "A.java", file, problems), "the second pass must reuse the entry");

        Assertions.assertEquals(1, cache.hits(), "the entry was reused");
        Assertions.assertEquals(1, cache.statHits(),
                "and it was reused by the STAT alone: no read of the source and no hash. A zero here means the gate "
                        + "did not run and every file was hashed as before");
        Assertions.assertEquals(0, cache.misses());
        Assertions.assertTrue(problems.isEmpty(), "a reuse is not a problem: " + problems);
    }

    @Test
    void aTouchedFileIsReusedButTakesTheHashTier(@TempDir Path tree) throws IOException {
        MetadataCache cache = MetadataCache.beside(tree.resolve(".jcodebuddy"));
        Path file = tree.resolve("A.java");
        Files.writeString(file, SOURCE);
        List<String> problems = new ArrayList<>();
        pass(cache, tree, "A.java", file, problems);

        // Rewrite identical bytes, so the content hash cannot change, and force a different mtime: this is the case
        // the maintainer named — "a touch is not a change" — and it must be a hit decided by the hash.
        Files.writeString(file, SOURCE);
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(120)));
        cache.resetCounters();
        Assertions.assertTrue(pass(cache, tree, "A.java", file, problems), "a touch must not invalidate the entry");
        Assertions.assertEquals(1, cache.hits());
        Assertions.assertEquals(0, cache.statHits(), "the stat DID move, so the hash had to decide");
        Assertions.assertEquals(0, cache.misses());
    }

    @Test
    void anEditedFileIsStaleAndIsNotReused(@TempDir Path tree) throws IOException {
        MetadataCache cache = MetadataCache.beside(tree.resolve(".jcodebuddy"));
        Path file = tree.resolve("A.java");
        Files.writeString(file, SOURCE);
        List<String> problems = new ArrayList<>();
        pass(cache, tree, "A.java", file, problems);

        Files.writeString(file, SOURCE.replace("A", "B"));
        cache.resetCounters();
        Assertions.assertFalse(pass(cache, tree, "A.java", file, problems),
                "the content changed, so the entry must not answer for it");
        Assertions.assertEquals(0, cache.hits());
        Assertions.assertEquals(1, cache.misses());
        Assertions.assertEquals(1, cache.entriesWritten(), "and the pass rewrote exactly this one entry");
    }

    @Test
    void anEntryWrittenBeforeTheTimestampExistedIsStillReusable(@TempDir Path tree) throws IOException {
        MetadataCache cache = MetadataCache.beside(tree.resolve(".jcodebuddy"));
        Path file = tree.resolve("A.java");
        Files.writeString(file, SOURCE);
        List<String> problems = new ArrayList<>();
        pass(cache, tree, "A.java", file, problems);

        // Simulate a legacy entry: the same facts with no recorded time, which is what every entry on disk looked
        // like before step 6.6. It must stay usable (the hash tier resolves it) and backfill on the next store.
        Path entry = cache.entryFile("A.java");
        String legacy = Files.readString(entry).replaceAll("\"lastModified\": -?\\d+,", "");
        Files.writeString(entry, legacy);
        Assertions.assertFalse(legacy.contains("lastModified"), "the fixture must really have dropped the field");

        cache.resetCounters();
        Assertions.assertTrue(pass(cache, tree, "A.java", file, problems),
                "an entry with no recorded time is resolved by the hash, not thrown away");
        Assertions.assertEquals(1, cache.hits());
        Assertions.assertEquals(0, cache.statHits(), "tier 2 decided it");
        Assertions.assertTrue(Files.readString(cache.entryFile("A.java")).contains("lastModified"),
                "the next store must backfill the field, so the old entry heals rather than stays slow");
    }
}
