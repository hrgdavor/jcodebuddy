package hr.hrg.jcodebuddy.automation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line classifier and the bounded validator, both of which are text-level by design.
 *
 * <p>The counts are the one fact {@link SourceFacts} derives from the text rather than from the tree,
 * because no LST carries them: comments are trivia the parser discards, and the tree has no line map —
 * the gap Phase 6 documented. What these tests therefore lock down is the state machine's behaviour at the
 * three places a per-line or regular-expression reading gets it wrong: a comment delimiter inside a
 * literal, a block comment spanning lines, and an unterminated literal.</p>
 */
class SourceFactsTest {

    @Test
    void countsCodeCommentAndBlankLines() {
        SourceFacts.LineCounts lines = SourceFacts.countLines("""
                package a;          // code and a trailing comment
                                    // a comment line
                /** a javadoc block
                 *  spanning three lines
                 */
                public class A {}
                """);

        assertEquals(6, lines.total());
        assertEquals(2, lines.code());
        assertEquals(4, lines.comment());
        assertEquals(0, lines.blank());
    }

    @Test
    void aCommentDelimiterInsideALiteralIsNotAComment() {
        SourceFacts.LineCounts lines = SourceFacts.countLines("""
                String url = "http://example.com"; // a real comment
                String pattern = "/* not a block comment */";
                char slash = '/';
                String text = \"""
                        // inside a text block: still code
                        /* and this too */
                        \""";
                """);

        // Only the first line has a real comment, but a line that holds code and a comment is a code line.
        // If the delimiters inside the literals were honoured, lines 2-7 would be misclassified.
        assertEquals(7, lines.total());
        assertEquals(7, lines.code());
        assertEquals(0, lines.comment());
        assertEquals(0, lines.blank());
    }

    @Test
    void aBlockCommentKeepsItsLinesAndAnUnterminatedLiteralFailsSafe() {
        SourceFacts.LineCounts comment = SourceFacts.countLines("""
                /*
                still inside
                */
                int x = 1;
                """);
        assertEquals(4, comment.total());
        assertEquals(1, comment.code());
        assertEquals(3, comment.comment());

        // An unterminated string leaves its own line as code and does not make the following lines read as
        // literal content — the same fail-safe direction SourceReader takes with unreadable input.
        SourceFacts.LineCounts unterminated = SourceFacts.countLines("String s = \"oops;\nint x = 1;\n");
        assertEquals(2, unterminated.total());
        assertEquals(2, unterminated.code());
    }

    @Test
    void theFinalNewlineIsNotALineButAGenuinelyBlankLineIs() {
        assertEquals(2, SourceFacts.countLines("a\nb\n").total());
        assertEquals(3, SourceFacts.countLines("a\nb\n\n").total());
        assertEquals(2, SourceFacts.countLines("\n\n").blank());
        assertEquals(0, SourceFacts.countLines("").total());
        assertEquals(0, SourceFacts.countLines(null).total());
    }

    @Test
    void factsOfAWellFormedFileCarryNoFindings() {
        SourceFacts.Facts facts = SourceFacts.of("Person.java", """
                package a.b;

                public class Person {
                }
                """);

        assertTrue(facts.readable());
        assertEquals(List.of(), facts.errors());
        assertEquals(List.of(), facts.warnings());
        assertEquals("a.b", facts.analysis().get(AnalysisResult.PACKAGE));
    }

    @Test
    void aPublicTypeMustBeNamedAfterItsFile() {
        SourceFacts.Facts facts = SourceFacts.of("Other.java", """
                package a.b;

                public class Person {
                }
                """);

        assertEquals(1, facts.errors().size());
        assertTrue(facts.errors().get(0).contains("does not match the file name Other.java"));
    }

    @Test
    void packageInfoIsAllowedToDeclareNothingAndADefaultPackageIsAWarning() {
        SourceFacts.Facts packageInfo = SourceFacts.of("package-info.java", "package a.b;\n");
        assertEquals(List.of(), packageInfo.errors());
        assertEquals(List.of(), packageInfo.warnings());

        SourceFacts.Facts defaultPackage = SourceFacts.of("Loose.java", "public class Loose {}\n");
        assertEquals(List.of(), defaultPackage.errors());
        assertTrue(defaultPackage.warnings().stream().anyMatch(warning -> warning.contains("no package")));
    }

    @Test
    void anEmptyFileIsAnErrorAndAnUnreadableOneSaysSo() {
        SourceFacts.Facts empty = SourceFacts.of("Empty.java", "   \n");
        assertFalse(empty.readable());
        assertEquals(1, empty.errors().size());
        assertTrue(empty.errors().get(0).contains("empty"));

        // `class` alone is accepted by the recovering parser and rejected by javac, so the reason has to
        // admit that the parser recorded nothing rather than pretend the file is readable.
        SourceFacts.Facts broken = SourceFacts.of("Broken.java", "class {");
        assertFalse(broken.readable());
        assertTrue(broken.errors().get(0).contains("not readable Java"));
    }
}
