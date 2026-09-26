package hr.hrg.hipster.entity.tooling;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * The marker vocabulary, from both sides: what a generator emits, and what a parser can decide.
 *
 * <p>DEC-035's claim is that an external parser or an AI agent can recognise generated code <b>without
 * knowing anything about the generator</b>. These tests are the claim made checkable, and they are written
 * from the parser's side — no generator instance, no config, no recognition logic of the generator's own.
 *
 * <p>The rule that carries the design is the last group: a marker this vocabulary does not implement must
 * be <em>recognised and reported</b>, never treated as hand-written. Everything else can be fixed by
 * updating a parser; treating generated code as the developer's is the one mistake that destroys work
 * silently.
 */
class GeneratedCodeMarkersTest {

    @Test
    @DisplayName("a file marker is recognised by its first line, and names the generator")
    void aFileMarkerIsRecognised() {
        String header = GeneratedCodeMarkers.fileHeader(
                "hr.hrg.example.Gen", "Thing metadata for the Thing view.");

        List<String> lines = List.of(header.split("\n"));

        Assertions.assertTrue(GeneratedCodeMarkers.isWhollyGenerated(lines),
                "the first line says the whole file is generated");

        GeneratedCodeMarkers.Found found = GeneratedCodeMarkers.recognise(1, lines.get(0)).orElseThrow();
        Assertions.assertEquals(GeneratedCodeMarkers.SCOPE_FILE, found.scope());
        Assertions.assertTrue(found.supported());
        Assertions.assertEquals("hr.hrg.example.Gen",
                GeneratedCodeMarkers.generatorOfFileMarker(lines).orElseThrow(),
                "the generator's FQN is recoverable, for a reader that wants to jump to it");
    }

    @Test
    @DisplayName("the config line is not a marker, so a parser never has to parse JSON5")
    void theConfigLineIsNotAMarker() {
        String header = GeneratedCodeMarkers.fileHeader("gen", "desc");

        Assertions.assertTrue(header.contains("// {enabled:true"),
                "the config line is still emitted, unchanged from DEC-021");
        Assertions.assertFalse(GeneratedCodeMarkers.isMarker("// {enabled:true, blockMarker: \"implicit\"}"),
                "it must not be mistaken for a marker: the boundary is the marker, the options are the "
                        + "generator's business");
    }

    @Test
    @DisplayName("blank lines before the header do not hide the file marker")
    void blankLinesDoNotHideTheMarker() {
        List<String> lines = List.of("", "   ", GeneratedCodeMarkers.fileHeader("gen", "desc").split("\n")[0]);

        Assertions.assertTrue(GeneratedCodeMarkers.isWhollyGenerated(lines));
    }

    @Test
    @DisplayName("a member and a block marker are recognised, and claim different scopes")
    void memberAndBlockAreRecognised() {
        GeneratedCodeMarkers.Found member =
                GeneratedCodeMarkers.recognise(10, GeneratedCodeMarkers.member("gen")).orElseThrow();
        GeneratedCodeMarkers.Found block =
                GeneratedCodeMarkers.recognise(20, GeneratedCodeMarkers.block("gen")).orElseThrow();

        Assertions.assertEquals(GeneratedCodeMarkers.SCOPE_MEMBER, member.scope());
        Assertions.assertEquals(GeneratedCodeMarkers.SCOPE_BLOCK, block.scope());
        Assertions.assertTrue(member.supported() && block.supported());
        Assertions.assertEquals(10, member.line(), "the report can cite the line");
    }

    @Test
    @DisplayName("a region pair is recognised and its id is recoverable")
    void aRegionPairIsRecognised() {
        String begin = GeneratedCodeMarkers.regionBegin("routes", "gen");
        String end = GeneratedCodeMarkers.regionEnd("routes");

        GeneratedCodeMarkers.Found found =
                GeneratedCodeMarkers.recognise(7, begin).orElseThrow();

        Assertions.assertEquals(GeneratedCodeMarkers.SCOPE_REGION, found.scope());
        Assertions.assertTrue(found.payload().startsWith("begin routes"),
                "the payload carries the pair id and the generator: " + found.payload());
        Assertions.assertTrue(end.contains("end routes"), "and the closing half names the same id");
    }

    @Test
    @DisplayName("the block marker exists so a switch is marked once, not per case arm")
    void theBlockMarkerIsForConstructsWithTheirOwnBraces() {
        // The case DEC-035 names: a generated `switch` over routes. One marker before the switch, and the
        // switch's braces bound it. A generator that marked each arm would add a line per route forever.
        List<String> lines = List.of(
                "    void route(String name) {",
                "        " + GeneratedCodeMarkers.block("gen"),
                "        switch (name) {",
                "            case \"a\" -> handleA();",
                "            case \"b\" -> handleB();",
                "            default -> handleDefault();",
                "        }",
                "    }");

        List<GeneratedCodeMarkers.Found> markers = GeneratedCodeMarkers.scan(lines);

        Assertions.assertEquals(1, markers.size(),
                "exactly one marker for the whole dispatch, not one per case: " + markers);
        Assertions.assertEquals(GeneratedCodeMarkers.SCOPE_BLOCK, markers.get(0).scope());
        Assertions.assertEquals(2, markers.get(0).line(), "and it sits immediately above the statement");
    }

    @Test
    @DisplayName("an unsupported marker is RECOGNISED and refused, never treated as hand-written")
    void anUnsupportedMarkerIsReportedNotIgnored() {
        // The rule the extensibility of the vocabulary rests on. A parser that has not implemented
        // `patchwork` must not conclude "not generated, safe to edit".
        String line = "        // @generated patchwork some-future-thing";

        GeneratedCodeMarkers.Found found = GeneratedCodeMarkers.recognise(412, line).orElseThrow();

        Assertions.assertFalse(found.supported(), "a scope this vocabulary does not define");
        Assertions.assertEquals("patchwork", found.scope());
        String report = found.unsupportedReport("Thing.java");
        Assertions.assertTrue(report.contains("@generated patchwork"), report);
        Assertions.assertTrue(report.contains("Thing.java:412"), "the report cites where: " + report);
        Assertions.assertTrue(report.contains("must not be treated as hand-written"),
                "and says what the parser must not do: " + report);
    }

    @Test
    @DisplayName("a bare @generated is a marker with no scope, and is refused rather than ignored")
    void aBareKeywordIsAMarker() {
        GeneratedCodeMarkers.Found found =
                GeneratedCodeMarkers.recognise(3, "// @generated").orElseThrow();

        Assertions.assertNull(found.scope());
        Assertions.assertFalse(found.supported(),
                "the keyword alone claims generated code and names no rule, which a parser cannot apply");
        Assertions.assertTrue(found.unsupportedReport("F.java").contains("no scope word"),
                found.unsupportedReport("F.java"));
    }

    @Test
    @DisplayName("prose that merely mentions the keyword is not a marker")
    void proseIsNotAMarker() {
        // The anchor is what keeps a document about markers — including DEC-035 itself — from scanning as
        // a marked-up file. A parser searching for the substring anywhere gets this wrong.
        Assertions.assertFalse(GeneratedCodeMarkers.isMarker(
                " * A marker is a Java line comment whose payload begins with @generated."));
        Assertions.assertFalse(GeneratedCodeMarkers.isMarker("// NOTE: @generated file x is the marker"));
        Assertions.assertFalse(GeneratedCodeMarkers.isMarker("int generated = 1;"));
        Assertions.assertFalse(GeneratedCodeMarkers.isMarker(null));
        // But leading space and a bare `//` are fine: indentation is where markers live.
        Assertions.assertTrue(GeneratedCodeMarkers.isMarker("        // @generated block gen"));
        Assertions.assertTrue(GeneratedCodeMarkers.isMarker("//@generated file gen"));
    }

    @Test
    @DisplayName("a scan lists every marker in order, and separates the unsupported ones")
    void aScanReportsWhatIsThere() {
        List<String> lines = List.of(
                GeneratedCodeMarkers.fileHeader("gen", "desc").split("\n")[0],
                "// {enabled:true}",
                "package p;",
                "public class C {",
                "    @generated-looking text, not a marker",
                "    " + GeneratedCodeMarkers.member("gen"),
                "    void m() {}",
                "    // @generated patchwork future",
                "}");

        Assertions.assertEquals(3, GeneratedCodeMarkers.scan(lines).size());
        List<GeneratedCodeMarkers.Found> unsupported = GeneratedCodeMarkers.unsupported(lines);
        Assertions.assertEquals(1, unsupported.size(),
                "only the marker this vocabulary cannot apply: " + unsupported);
        Assertions.assertEquals("patchwork", unsupported.get(0).scope());
        Assertions.assertEquals(8, unsupported.get(0).line());
    }

    @Test
    @DisplayName("a file with no marker is not wholly generated")
    void anUnmarkedFileIsNotGenerated() {
        Assertions.assertFalse(GeneratedCodeMarkers.isWhollyGenerated(List.of(
                "package p;", "public class Hand {", "}")));
        Assertions.assertFalse(GeneratedCodeMarkers.isWhollyGenerated(List.of()));
        // A hand-written file that mentions the marker deeper down is still not wholly generated: only the
        // first non-blank line decides.
        Assertions.assertFalse(GeneratedCodeMarkers.isWhollyGenerated(List.of(
                "package p;",
                "    " + GeneratedCodeMarkers.member("gen"))));
    }

    @Test
    @DisplayName("every emitted marker shape is one this vocabulary recognises")
    void everyEmittedShapeIsRecognised() {
        // The two sides cannot drift: whatever a generator builds, the recogniser must accept. This is the
        // test that makes "the spelling lives in one place" a fact rather than a convention.
        List<String> emitted = List.of(
                GeneratedCodeMarkers.fileHeader("a.b.Gen", "d").split("\n")[0],
                GeneratedCodeMarkers.fieldEnumFileHeader("a.b.Gen", "d").split("\n")[0],
                GeneratedCodeMarkers.member("a.b.Gen"),
                GeneratedCodeMarkers.block("a.b.Gen"),
                GeneratedCodeMarkers.regionBegin("id", "a.b.Gen"),
                GeneratedCodeMarkers.regionEnd("id"));

        for (String line : emitted) {
            Optional<GeneratedCodeMarkers.Found> found = GeneratedCodeMarkers.recognise(1, line);
            Assertions.assertTrue(found.isPresent(), "not recognised: " + line);
            Assertions.assertTrue(found.get().supported(), "recognised but not supported: " + line);
        }
    }

    @Test
    @DisplayName("the field-enum header keeps the extra DEC-021 option the enum needs")
    void theFieldEnumHeaderKeepsItsOption() {
        String header = GeneratedCodeMarkers.fieldEnumFileHeader("a.b.Gen", "d");

        Assertions.assertTrue(header.contains("entityFieldEnum:true"),
                "the R1 marker the field enum depends on: " + header);
        Assertions.assertTrue(header.startsWith("// @generated file "), header);
    }

    @Test
    @DisplayName("isKnownScope answers for the four defined scopes and nothing else")
    void knownScopesAreExactlyFour() {
        Assertions.assertEquals(4, GeneratedCodeMarkers.KNOWN_SCOPES.size());
        for (String scope : List.of("file", "member", "region", "block")) {
            Assertions.assertTrue(GeneratedCodeMarkers.isKnownScope(scope));
        }
        Assertions.assertFalse(GeneratedCodeMarkers.isKnownScope("patchwork"));
        Assertions.assertFalse(GeneratedCodeMarkers.isKnownScope(null));
    }
}
