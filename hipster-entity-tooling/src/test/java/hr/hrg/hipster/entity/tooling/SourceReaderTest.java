package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared read: {@link SourceReader} must reject a partial parse, and its parser must be able to
 * read the language the project actually writes.
 *
 * <p>Both halves are here because the second one was found <em>by</em> the first, during the
 * follow-up implementation. Pinning {@code isSuccessful()} made the generator report its own output
 * as unreadable, and the reason was that a bare {@code new JavaParser()} parses at
 * {@code LanguageLevel.POPULAR} — Java 11 — which has no switch expressions and no records. The
 * generated {@code get(int)} of every builder is a switch expression and the whole {@code RECORD}
 * level is records, so a large part of the tree was one silent parse failure away from being
 * "invisible" — the failure mode F-23 records in its extreme form (a JavaParser too old for
 * {@code sealed} made five example files generate nothing, with no error).</p>
 */
class SourceReaderTest {

    @Test
    void aSwitchExpressionAndARecordParseCleanly() {
        // Both features are emitted by this generator, so a parser that cannot read them cannot read
        // the tree it generates into. This is the assertion that would have failed before the level
        // was pinned.
        String source = """
                package p;
                public class Sample {
                    public Object get(int ordinal) {
                        return switch (ordinal) {
                            case 0 -> "id";
                            default -> null;
                        };
                    }
                    public record Row(Long id, String name) {}
                }
                """;

        SourceReader.Read read = SourceReader.readText(source);

        Assertions.assertTrue(read.readable(),
                "the generator's own output shape must be readable at the project's language level");
    }

    @Test
    void theParserIsConfiguredForTheProjectsTargetNotTheJavaParserDefault() {
        // Pinned directly, because the symptom is what matters: `LanguageLevel.POPULAR` is Java 11 and
        // silently rejects modern syntax. Asserting the configured level keeps a future "tidy-up" from
        // dropping the configuration and re-introducing an invisible partial parse.
        Assertions.assertEquals(
                com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_25,
                SourceReader.parser().getParserConfiguration().getLanguageLevel(),
                "the shared parser must match the root POM's maven.compiler.release=25");
    }

    @Test
    void genuinelyBrokenSourceIsUnparseableRatherThanPartiallyRead() {
        // F-34's case: the bracket of a constant is broken. JavaParser returns a PARTIAL unit for
        // this, so "a result was produced" is not "the file is readable".
        String source = """
                package p;
                public enum Broken_ {
                    id(java.lang.Long.class;
                }
                """;

        SourceReader.Read read = SourceReader.readText(source);

        Assertions.assertFalse(read.readable(), "a partial parse is not a read");
        Assertions.assertTrue(read.unparseable(), "and it is reported as such, not as a missing file");
    }

    @Test
    void aMissingFileIsAnEmptyReadNotAnUnparseableOne() throws Exception {
        Path missing = Files.createTempDirectory("source-reader").resolve("nope.java");

        SourceReader.Read read = SourceReader.read(missing);

        Assertions.assertFalse(read.readable());
        Assertions.assertFalse(read.unparseable(),
                "no file is the normal fresh-generation case; a broken file is the reported one");
    }

    @Test
    void aRealFileOnDiskReadsThroughThePathEntryPoint() throws Exception {
        Path file = Files.createTempDirectory("source-reader-ok").resolve("Ok.java");
        Files.writeString(file, "package p;\npublic interface Ok {}\n");

        SourceReader.Read read = SourceReader.read(file);

        Assertions.assertTrue(read.readable());
        Assertions.assertEquals("p", read.unit().getPackageDeclaration().orElseThrow().getNameAsString());
    }
}
