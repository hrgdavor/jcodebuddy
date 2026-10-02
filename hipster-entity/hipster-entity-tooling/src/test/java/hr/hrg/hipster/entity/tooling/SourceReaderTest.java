package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.tree.J;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    /**
     * The readability contract, on the two fixtures F-34 is about.
     *
     * <p>Phase 6: this test used to compare the JavaParser bridge against the LST path, because both
     * had to answer "could this be read at all" the same way while the migration was in flight. The
     * bridge is gone with its last caller, so what remains is the contract itself: a clean file reads,
     * and a file with a syntax error inside an enum does <strong>not</strong> — the case where the
     * parser recovers, hides the error in a well-formed tree, and would otherwise let a pass treat a
     * broken ledger as an empty one.</p>
     */
    @Test
    void aCleanFileReadsAndARecoveredSyntaxErrorDoesNot() {
        String good = "package p;\npublic interface Ok {}\n";
        String broken = "package p;\npublic enum Broken_ { id(java.lang.Long.class; }\n";

        Assertions.assertTrue(SourceReader.readText(good).readable());
        Assertions.assertFalse(SourceReader.readText(broken).readable(),
                "a recovered parse is not a readable file: the tree looks well-formed and the file is not");
        Assertions.assertNull(SourceReader.readSourceText(broken));
    }

    /**
     * The javac position lookup resolves a declaration to the line of its <strong>name</strong>.
     *
     * <p>This is the working half of the line-number feature, asserted directly by
     * {@code (simpleName, enclosingChain)} rather than through any LST walk — the walk consumed it
     * wrongly three times, so the two halves are pinned separately.</p>
     */
    @Test
    void javacPositionsResolveTheNameLine() {
        String source = "package a;\n\n@Deprecated\npublic class Marked {\n}\n";
        List<JavaSyntaxCheck.TypePosition> positions = JavaSyntaxCheck.typeNameLines(source);
        Assertions.assertEquals(1, positions.size());
        JavaSyntaxCheck.TypePosition marked = positions.get(0);
        Assertions.assertEquals("Marked", marked.simpleName());
        Assertions.assertEquals(List.of(), marked.enclosingNames());
        Assertions.assertEquals(4, marked.nameLine(),
                "the name is on line 4; the annotation is on line 3, and linking to the annotation "
                        + "opens the wrong code while looking authoritative");
    }

    /** A nested type's chain is its ancestors, outermost first — the key the line lookup matches on. */
    @Test
    void javacPositionsCarryTheEnclosingChain() {
        String source = """
                package a.b;
                public sealed interface Shape permits Shape.Circle {
                    record Circle(double radius) implements Shape {}
                }
                """;
        List<JavaSyntaxCheck.TypePosition> positions = JavaSyntaxCheck.typeNameLines(source);
        Assertions.assertEquals(2, positions.size());

        JavaSyntaxCheck.TypePosition shape = positions.get(0);
        Assertions.assertEquals("Shape", shape.simpleName());
        Assertions.assertEquals(List.of(), shape.enclosingNames());
        Assertions.assertEquals(2, shape.nameLine());

        JavaSyntaxCheck.TypePosition circle = positions.get(1);
        Assertions.assertEquals("Circle", circle.simpleName());
        Assertions.assertEquals(List.of("Shape"), circle.enclosingNames(),
                "the enclosing chain is outermost-first and excludes the declaration itself");
        Assertions.assertEquals(3, circle.nameLine());
    }

    /**
     * A method's line is its <strong>name's</strong>, and an annotation's line is its own.
     *
     * <p>Both are needed by the metadata generator's location payload (DEC-028), and both exist because
     * the LST exposes no positions. The distinction is the point: an accessor carrying an annotation
     * begins on the annotation's line, so recording one line for both is the defect F-46 records —
     * {@code PersonSummary.age} is 17 for {@code Integer age();} and 16 for the {@code @FieldSource}
     * above it.</p>
     */
    @Test
    void methodAndAnnotationLinesAreResolvedSeparately() {
        String source = """
                package p;
                public interface V {
                    @Deprecated
                    String age();

                    Integer count();
                }
                """;
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        Assertions.assertNotNull(unit, "the fixture must parse");

        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(TreeQueries.interfaces(unit).get(0));
        Assertions.assertEquals(2, methods.size());

        J.MethodDeclaration age = methods.get(0);
        Assertions.assertEquals("age", age.getSimpleName());
        Assertions.assertEquals(4, TreeQueries.methodLineOf(age, "V", source),
                "the accessor's name is on line 4, not the annotation's line 3");

        J.MethodDeclaration count = methods.get(1);
        Assertions.assertEquals(6, TreeQueries.methodLineOf(count, "V", source));

        Assertions.assertEquals(3, TreeQueries.annotationLineOf("age", "Deprecated", source),
                "the annotation has a line of its own, which is what makes the accessor's line useful");
    }

    /**
     * Validity and positions come from one parse, and a repeat call reuses it.
     *
     * <p>Asserted because the consolidation is the point: before this, the validity check and the line
     * lookup each built their own javac file manager, so a pass over a tree opened two handles per file
     * and answered the same question twice.</p>
     */
    @Test
    void inspectionAnswersValidityAndPositionsFromOneResult() {
        String source = "package p;\npublic interface V {\n    String name();\n}\n";
        JavaSyntaxCheck.FileCheck check = JavaSyntaxCheck.inspect(source);

        Assertions.assertTrue(check.syntacticallyValid());
        Assertions.assertEquals(1, check.types().size());
        Assertions.assertEquals(1, check.methods().size());
        Assertions.assertEquals("name", check.methods().get(0).simpleName());
        Assertions.assertEquals(0, check.methods().get(0).parameterCount());
        Assertions.assertSame(check, JavaSyntaxCheck.inspect(source),
                "a repeat call must reuse the parse rather than run javac again");
    }

    /**
     * How the LST models a {@code void} method's return type.
     *
     * <p>Pinned because the metadata generator's accessor filter excludes {@code void} methods, and
     * JavaParser's {@code getType().isVoidType()} has no direct equivalent. The obvious replacement —
     * "there is no return-type expression" — is <strong>wrong</strong>: measured, a {@code void} method
     * has a {@code J.Primitive} return type that prints as {@code void}. A port that assumed the null
     * shape would let every mutation method through the filter as a field.</p>
     */
    @Test
    void aVoidMethodIsAPrimitiveReturnTypeNotNull() {
        String source = """
                package p;
                public interface V {
                    void act();
                    String name();
                }
                """;
        J.CompilationUnit unit = SourceReader.readSourceText(source);
        List<J.MethodDeclaration> methods = TreeQueries.methodsOf(TreeQueries.interfaces(unit).get(0));

        J.MethodDeclaration act = methods.get(0);
        Assertions.assertEquals("act", act.getSimpleName());
        Assertions.assertNotNull(act.getReturnTypeExpression(),
                "`void` is a Primitive return type, not an absent one");
        Assertions.assertTrue(TreeQueries.isVoidReturn(act),
                "`void act()` must be recognised as a void return");

        J.MethodDeclaration name = methods.get(1);
        Assertions.assertFalse(TreeQueries.isVoidReturn(name), "a real return type is not void");
    }

    /**
     * The four entry points this class documents itself around, tested directly.
     *
     * <p>{@link SourceReader#read(Path)} and {@link SourceReader#readSourceText(String)} were reached by
     * every generator test in the module; these four were reached by none. Three of them are the reason
     * the class exists at all — {@link SourceReader#readUnit(Path)} is the seam a caller outside the
     * package uses, {@link SourceReader#problemsIn(String)} is the detail behind a refusal — measured
     * here, often empty even when the file is refused — and
     * {@link SourceReader#reportUnparseable} is where the fail-safe becomes a line a human
     * reads — so an untested trio on a class whose whole job is failure handling is the gap this closes.</p>
     */
    @org.junit.jupiter.api.Nested
    class OtherEntryPoints {

        /** A file on disk, read the way a caller outside this package reads one. */
        @org.junit.jupiter.api.Test
        void readUnitReturnsATreeOrNothingNeverAPartialOne(@org.junit.jupiter.api.io.TempDir Path dir)
                throws Exception {
            Path good = dir.resolve("Good.java");
            Files.writeString(good, "package p;\npublic class Good { void m() { } }\n");
            Path broken = dir.resolve("Broken.java");
            Files.writeString(broken, "package p;\npublic enum Broken_ {\n    id(java.lang.Long.class;\n}\n");

            Assertions.assertNotNull(SourceReader.readUnit(good), "a readable file yields its tree");
            Assertions.assertNull(SourceReader.readUnit(broken),
                    "a file javac recovers from yields nothing, not a half-tree that looks complete");
            Assertions.assertNull(SourceReader.readUnit(dir.resolve("Absent.java")),
                    "a missing file is not an error and yields nothing either");
            Assertions.assertNull(SourceReader.readUnit(null));
        }

        @org.junit.jupiter.api.Test
        void problemsAreDetailAndNeverTheVerdict() {
            Assertions.assertEquals(List.of(), SourceReader.problemsIn(
                    "package p;\npublic class Clean { }\n"), "a clean file has nothing to report");
            Assertions.assertEquals(List.of("the source was null"), SourceReader.problemsIn(null),
                    "and a null source is reported as itself rather than as an empty list");

            // The asymmetry that matters. OpenRewrite recovers from this fixture and reports nothing, so
            // `problemsIn` comes back empty while `readText` refuses: a caller may use the messages as
            // detail, and must use `readable()` as the verdict. Pinned because the obvious implementation
            // of "why did this fail?" is `if (problems.isEmpty()) it is fine`, which is exactly F-34.
            String recovered = "package p;\npublic enum Broken_ {\n    id(java.lang.Long.class;\n}\n";
            Assertions.assertFalse(SourceReader.readText(recovered).readable(),
                    "the read must refuse it");
            Assertions.assertTrue(SourceReader.readText(recovered).unparseable(),
                    "and say so through the dedicated flag, not through a null unit alone");

            // A failure OpenRewrite does not report either. Measured: a truncated method body produces no
            // message at all, even though javac rejects the file — so `problemsIn` is not a validator and
            // cannot be used as one. Two shapes now pin this: the F-34 enum above, and this.
            List<String> truncated = SourceReader.problemsIn(
                    "package p;\npublic class C { void m() { x( }\n}\n");
            Assertions.assertTrue(truncated.isEmpty(),
                    "measured: the parser's own list is empty for a file javac rejects, which is exactly "
                            + "why the verdict comes from readable() (" + truncated + ")");
            Assertions.assertFalse(SourceReader.readText(
                            "package p;\npublic class C { void m() { x( }\n}\n").readable(),
                    "and the verdict does reject it");
        }

        @org.junit.jupiter.api.Test
        void theFragmentWrapperHoldsTheExpressionAndNonsenseIsRecoveredNotRefused() {
            // `new Class<?>[0]` is the case the javadoc names: an expression JavaParser used to parse
            // through the leaked parser accessor.
            J.CompilationUnit wrapper = SourceReader.readFragmentUnit("new Class<?>[0]");
            Assertions.assertNotNull(wrapper);
            List<J.MethodDeclaration> methods = TreeQueries.methodsOf(TreeQueries.classes(wrapper).get(0));
            Assertions.assertEquals(List.of(), methods, "the wrapper holds a field, not a method");
            Assertions.assertTrue(wrapper.printAll().contains("new Class<?>[0]"),
                    "the fragment survives the wrapper unchanged, which is what lets the caller pull it "
                            + "back out: " + wrapper.printAll());

            // Measured against the javadoc, which promises an IllegalArgumentException "if the fragment
            // does not parse": a fragment that is *not* an expression still parses inside the wrapper, as
            // garbage, and is returned. The exception branch only fires when OpenRewrite itself reports a
            // failure. Stated here because the difference matters to a caller that reads the javadoc and
            // assumes a non-null answer means "this was a valid expression" — it does not.
            J.CompilationUnit nonsense = SourceReader.readFragmentUnit("this is not an expression at all");
            Assertions.assertNotNull(nonsense, "the wrapper recovers rather than refusing");
            Assertions.assertFalse(TreeQueries.classes(nonsense).isEmpty(),
                    "and hands back the wrapper, leaving the caller to check the shape it asked for");
        }

        @org.junit.jupiter.api.Test
        void theFailSafeReportSaysWhatWasNotDone() {
            DivergenceReporter divergences = new DivergenceReporter();
            SourceReader.reportUnparseable(divergences, "source_not_parsed", "hr/Person_.java",
                    "the parser recovered from a syntax error", "regenerate hr/Person_.java");

            List<String> lines = divergences.ofKind("source_not_parsed");
            Assertions.assertEquals(1, lines.size(), "one DEC-022 line, in the reporter's own order");
            String line = lines.get(0);
            Assertions.assertTrue(line.contains("location=hr/Person_.java"), line);
            Assertions.assertTrue(line.contains("unparseable source"), line);
            Assertions.assertTrue(line.contains("did NOT regenerate hr/Person_.java"),
                    "the action names the consequence, because that is the half a reader acts on: " + line);

            // The null reporter is a real caller shape (a run with no divergence channel), and it must
            // stay a no-op rather than turning a read failure into a crash.
            SourceReader.reportUnparseable(null, "source_not_parsed", "x", "y", "z");
        }
    }
}
