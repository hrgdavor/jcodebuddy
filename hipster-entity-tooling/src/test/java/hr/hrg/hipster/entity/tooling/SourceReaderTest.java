package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.J;

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
 *
 * <h3>Phase 6: what changed here, and what deliberately did not</h3>
 * <p>The read now parses with OpenRewrite, and the language level is no longer a constant this class
 * sets: it is the {@code rewrite-java-25} artifact on the classpath. That makes the second test below
 * <strong>more</strong> important, not less. The old version asserted the configured
 * {@code LanguageLevel} value, which is a proxy for the property that actually matters. The
 * replacement asserts the property itself — that a record, a switch expression and a {@code sealed}
 * type all parse — so it keeps guarding the same failure while surviving the change of parser. It is
 * the regression test that must fail if someone swaps the version-specific parser artifact for an
 * older one.</p>
 *
 * <p>The first and third tests are unchanged in intent: a clean parse is readable, and a broken file
 * is reported as unparseable rather than being mistaken for an empty one (F-34). The file-level test
 * now reads the package through {@link TreeQueries} instead of JavaParser's
 * {@code getPackageDeclaration().orElseThrow()}.</p>
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

    /**
     * The property the old {@code LanguageLevel.JAVA_25} constant stood for, asserted directly.
     *
     * <p>{@code sealed} is the sharpest of the three: it is the feature whose absence made five
     * example files generate nothing with no error at all (F-23), because a parser below Java 17
     * rejects the whole file rather than the construct. A parser artifact that is too old, or a
     * module that lost {@code rewrite-java-25}, fails here.</p>
     */
    @Test
    void theParserReadsTheLanguageTheProjectTargets() {
        String source = """
                package p;
                public sealed interface Payment permits Card, Cash {
                    record Card(String pan) implements Payment {}
                    record Cash(double amount) implements Payment {}
                }
                """;

        SourceReader.Read read = SourceReader.readText(source);

        Assertions.assertTrue(read.readable(),
                "the shared parser must match the root POM's maven.compiler.release=25: a parser below "
                        + "the project's level cannot read the output it generates (F-23)");
    }

    @Test
    void genuinelyBrokenSourceIsUnparseableRatherThanPartiallyRead() {
        // F-34's case: the bracket of a constant is broken. JavaParser returned a PARTIAL unit for
        // this, so "a result was produced" is not "the file is readable". OpenRewrite goes further and
        // returns a *well-formed* unit, which is why this reader asks javac (see JavaSyntaxCheck).
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
        J.CompilationUnit unit = read.unit();
        Assertions.assertEquals("p", TreeQueries.packageName(unit));
        Assertions.assertEquals(1, TreeQueries.interfaces(unit).size());
        Assertions.assertEquals("Ok", TreeQueries.interfaces(unit).get(0).getSimpleName());
    }

    /**
     * The kind test is the trap the port has to keep: one {@code J.ClassDeclaration} covers all five
     * kinds, so {@code interfaces} must not return the file's records or enums.
     */
    @Test
    void kindQueriesDoNotConfuseRecordsAndEnumsForInterfaces() {
        String source = """
                package p;
                public interface View {}
                public record Row(Long id) {}
                public enum Level { LOW, HIGH }
                public @interface Marker {}
                """;

        J.CompilationUnit unit = SourceReader.readSourceText(source);

        Assertions.assertNotNull(unit, "the fixture must parse");
        Assertions.assertEquals(1, TreeQueries.interfaces(unit).size(),
                "an interface search must not match the record, the enum or the annotation");
        Assertions.assertEquals(1, TreeQueries.records(unit).size());
        Assertions.assertEquals(1, TreeQueries.enums(unit).size());
        Assertions.assertEquals(1, TreeQueries.annotations(unit).size());
        Assertions.assertEquals(4, TreeQueries.typeDeclarations(unit).size());
    }

    /** The un-ported bridge must answer the same "readable?" question as the LST path. */
    @Test
    void thePortingBridgeAgreesWithTheLstPathAboutReadability() {
        String good = "package p;\npublic interface Ok {}\n";
        String broken = "package p;\npublic enum Broken_ { id(java.lang.Long.class; }\n";

        Assertions.assertTrue(SourceReader.readJpText(good).readable());
        Assertions.assertFalse(SourceReader.readJpText(broken).readable());
        Assertions.assertTrue(SourceReader.readText(good).readable());
        Assertions.assertFalse(SourceReader.readText(broken).readable());
    }

}
