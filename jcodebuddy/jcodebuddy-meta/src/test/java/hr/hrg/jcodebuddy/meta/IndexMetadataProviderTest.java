package hr.hrg.jcodebuddy.meta;

import hr.hrg.jcodebuddy.engine.index.ClassIndex;
import hr.hrg.jcodebuddy.engine.source.SourceReader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The engine-backed provider: DEC-W008's {@code parse} from source bytes, and the class and type questions answered
 * from the class index (plan step 3.0j).
 *
 * <p>The parse cases moved here from {@code project-automation} with the code, because the method they test is now
 * the interface's own default — the reader it needs lives in the engine, which is what let DEC-W008's original
 * requirement be met instead of waived. The properties are the ones that make it usable in a fresh checkout: it is
 * a function of its two arguments, the checksum is stable across line endings, a broken file is reported rather
 * than invented, and the primary type is the one the file is named after. A test that only checked "the class name
 * came back" would pass for an implementation that quietly read the filesystem or a stale entry.</p>
 */
class IndexMetadataProviderTest {

    private static final String VIEW = """
            package demo.hr;

            import java.util.List;

            public interface PersonSummary {
                String firstName();
                Integer age();
                List<String> tags();
            }
            """;

    private static MetadataProvider.CacheEntry parse(String path, String text) {
        return IndexMetadataProvider.parseSource(path, text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aFileScopedEntryCarriesItsIdentityItsTypeAndItsOwnFacts() {
        MetadataProvider.CacheEntry entry = parse("demo/hr/PersonSummary.java", VIEW);

        Assertions.assertEquals("demo/hr/PersonSummary.java", entry.relativePath(),
                "the path is passed through, never resolved against a filesystem");
        Assertions.assertEquals("demo.hr.PersonSummary", entry.fullClassName(),
                "the primary type's fully qualified name, from the package plus the file's type");
        Assertions.assertEquals(16, entry.hash().length(), "16 hex characters (DEC-029 § 4)");
        Assertions.assertTrue(entry.hash().matches("[0-9a-f]{16}"), entry.hash());

        java.util.Map<String, Object> metadata = entry.metadata();
        Assertions.assertEquals(Boolean.TRUE, metadata.get("readable"));
        Assertions.assertEquals("demo.hr", metadata.get("package"));
        Assertions.assertEquals("PersonSummary", metadata.get("simpleName"));
        Assertions.assertEquals("interface", metadata.get("kind"),
                "the kind comes from the engine's own model, not from a second guess here");
        Assertions.assertEquals(List.of("age", "firstName", "tags"), metadata.get("methods"),
                "declared methods, sorted: the declaration order is not part of the fact");
        Assertions.assertEquals(List.of("demo.hr.PersonSummary"), metadata.get("classes"),
                "and every type the file declares, which is what makes listClasses answerable elsewhere");
        Assertions.assertFalse(metadata.containsKey("references"),
                "nothing needing a second file may appear: DEC-W008 puts cross-file facts in DEC-W009's store");
    }

    @Test
    void theChecksumIsAPropertyOfTheContentAndNotOfTheCheckout() {
        String crlf = VIEW.replace("\n", "\r\n");
        MetadataProvider.CacheEntry unix = parse("demo/hr/PersonSummary.java", VIEW);
        MetadataProvider.CacheEntry windows = parse("demo/hr/PersonSummary.java", crlf);

        Assertions.assertEquals(unix.hash(), windows.hash(),
                "a CRLF checkout and an LF checkout of the same content must agree, or the checksum describes "
                        + "someone's git configuration (ContentHash, DEC-029 § 4)");
        Assertions.assertEquals(unix.fullClassName(), windows.fullClassName(),
                "and the rest of the answer does not depend on line endings either");
    }

    @Test
    void aBrokenFileIsReportedRatherThanInvented() {
        MetadataProvider.CacheEntry entry = parse("demo/hr/Broken.java",
                "package demo.hr;\n\npublic class Broken {\n");

        Assertions.assertEquals("", entry.fullClassName(),
                "no type can be named from an unreadable file, and an invented name would be a claim about source "
                        + "nobody parsed");
        Assertions.assertFalse(entry.hash().isEmpty(),
                "the entry still exists and still carries its checksum, so 'this file changed' and 'this file is "
                        + "broken' stay distinguishable");
        Assertions.assertEquals(Boolean.FALSE, entry.metadata().get("readable"));
        Assertions.assertTrue(entry.metadata().containsKey("problems"),
                "the reader's own detail travels with the answer, so a caller can show it");
        Assertions.assertNotNull(entry.metadata().get("problems"));
    }

    @Test
    void thePrimaryTypeIsTheOneTheFileIsNamedAfter() {
        // Both types are legal top-level declarations; only the file-name match is the file's primary one, and a
        // parser that took the first declaration would report First.
        String twoTypes = """
                package demo.hr;

                class First {
                }

                class PersonSummary {
                }
                """;

        MetadataProvider.CacheEntry entry = parse("demo/hr/PersonSummary.java", twoTypes);

        Assertions.assertEquals("demo.hr.PersonSummary", entry.fullClassName(),
                "Java's rule: the type the file is named after");
    }

    @Test
    void aFileWithNoTypeIsNotAnError() {
        MetadataProvider.CacheEntry entry = parse("demo/hr/package-info.java", "package demo.hr;\n");

        Assertions.assertEquals("", entry.fullClassName());
        Assertions.assertEquals(Boolean.TRUE, entry.metadata().get("readable"),
                "an empty but valid file is readable; only a broken one is not");
        Assertions.assertEquals(List.of(), entry.metadata().get("methods"));
    }

    @Test
    void theProviderAnswersTheClassAndTypeQuestionsFromTheIndex(@TempDir Path tree) throws IOException {
        // The question WatchMetadataProvider cannot answer, and the reason this provider exists: what TYPES does
        // this project have, and what identity does each file have now.
        ClassIndex index = ClassIndex.forPass(tree.resolve(".jcodebuddy"), tree, tree.resolve("src"));
        addFile(index, tree, "src/a/b/First.java", "package a.b;\n\npublic class First {\n    int id;\n}\n");
        addFile(index, tree, "src/a/b/Second.java",
                "package a.b;\n\npublic interface Second {\n    String name();\n}\n");
        index.write();

        IndexMetadataProvider provider = IndexMetadataProvider.of(index);
        Assertions.assertEquals(List.of("a.b.First", "a.b.Second"), provider.listClasses(),
                "the project's real type names, in FQN order — not an empty list and not an invention");
        Assertions.assertEquals(2, provider.listEntries().size(), "one entry per row");

        MetadataProvider.CacheEntry entry = provider.listEntries().get(0);
        Assertions.assertEquals("a.b.First", entry.fullClassName(), "an entry names the type it describes");
        Assertions.assertEquals(entry.hash(), provider.get(entry.hash()).hash(),
                "and is reachable by its content hash, which is the interface's identity");
        Assertions.assertFalse(provider.hasChanged("src/a/b/First.java", entry.hash()),
                "the row's checksum is the file's current identity, so nothing has changed");
        Assertions.assertTrue(provider.hasChanged("src/a/b/First.java", "0000000000000000"),
                "while a different checksum means it has");
        Assertions.assertTrue(provider.hasChanged("src/a/b/Gone.java", entry.hash()),
                "and a path with no row has changed as far as the index can tell");
    }

    @Test
    void readingATableThatIsNotThereYieldsAProviderThatKnowsNothing(@TempDir Path tree) {
        IndexMetadataProvider provider = IndexMetadataProvider.reading(tree.resolve("absent/classes.json"),
                tree.resolve("absent"), tree, tree.resolve("src"));

        Assertions.assertEquals(List.of(), provider.listClasses(), "no index yet is a normal state, not an error");
        Assertions.assertEquals(List.of(), provider.listEntries());
        Assertions.assertNull(provider.get("0000000000000000"));
        Assertions.assertTrue(provider.hasChanged("src/a/b/First.java", "0000000000000000"));
        // And parse still works, because it never needed the index: it is the file-scoped path.
        Assertions.assertEquals("demo.hr.PersonSummary", parse("demo/hr/PersonSummary.java", VIEW).fullClassName());
    }

    /**
     * Adds one file to the index <em>and to disk</em>: a row whose size and checksum describe a file nobody read
     * is refused when the table is written, which is the engine's own guard and the reason this helper exists.
     */
    private static void addFile(ClassIndex index, Path tree, String relativePath, String source) throws IOException {
        Path file = tree.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        index.addTypes(relativePath, unitOf(source), source, false);
    }

    private static org.openrewrite.java.tree.J.CompilationUnit unitOf(String source) {
        SourceReader.Read read = SourceReader.readText(source);
        Assertions.assertTrue(read.readable(), "the fixture must parse");
        return read.unit();
    }
}
