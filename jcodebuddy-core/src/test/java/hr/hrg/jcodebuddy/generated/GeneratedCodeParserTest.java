package hr.hrg.jcodebuddy.generated;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * The parser, from the side of a tool that is not the generator.
 *
 * <p>Every test here builds source text and reads spans back through the public API. No generator is
 * constructed, no configuration is passed and no generator type is referenced anywhere in the file — which
 * is the property DEC-035 exists for, and the one a test can quietly break by reaching for a convenience.
 *
 * <p>The tests are grouped by what a consumer actually needs to decide:
 *
 * <ul>
 *   <li>where the generated code is, for each of the four scopes;</li>
 *   <li>that the extent is right when the Java around it is awkward — braces in strings, text blocks,
 *       comments and escaped quotes, which a brace counter gets wrong silently;</li>
 *   <li>that a marker which cannot be applied is <em>reported</em>, because the behaviour DEC-035 forbids
 *       is treating generated code as hand-written.</li>
 * </ul>
 */
class GeneratedCodeParserTest {

    private static final String GEN = "hr.hrg.example.Gen";

    // ---- the four scopes ----

    @Test
    @DisplayName("a file marker covers the whole file, so nothing has to be matched")
    void fileMarkerCoversEverything() {
        String source = GeneratedCodeMarkers.fileHeader(GEN, "Thing metadata.")
                + "package p;\n\npublic enum Thing_ {\n}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "Thing_.java");

        Assertions.assertTrue(result.isWhollyGenerated());
        Assertions.assertEquals(1, result.blocks().size());
        GeneratedBlock block = result.blocks().get(0);
        Assertions.assertEquals(GeneratedBlock.Kind.FILE, block.kind());
        Assertions.assertEquals(1, block.fromLine(), "the marker line is part of its own span");
        Assertions.assertEquals(result.lines(), block.toLine());
        Assertions.assertTrue(block.closed());
        Assertions.assertFalse(result.hasIssues());
    }

    @Test
    @DisplayName("the block's span is inclusive of the marker, so replacing it replaces the marker too")
    void theMarkerLineIsInsideTheSpan() {
        // This is the choice that makes a block usable for replacement: a tool that rewrites lines 3..6
        // must not leave the marker behind as an orphan.
        String source = """
                package p;
                class C {
                    // @generated block %s
                    void m() {
                        call();
                    }
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        GeneratedBlock block = result.blocks().get(0);
        Assertions.assertEquals(3, block.fromLine(), "the marker's own line");
        Assertions.assertEquals(6, block.toLine(), "through the method's closing brace");
        Assertions.assertEquals(4, block.lineCount());
        Assertions.assertTrue(result.isGenerated(3), "the marker line is generated");
        Assertions.assertTrue(result.isGenerated(5), "so is the body");
        Assertions.assertFalse(result.isGenerated(7), "and the line after the brace is not");
    }

    @Test
    @DisplayName("a member marker spans the declaration and its body, not the next declaration")
    void memberMarkerSpansItsOwnBody() {
        String source = """
                package p;
                class C {
                    // @generated member %s
                    public int value() {
                        return 1;
                    }

                    public int handWritten() {
                        return 2;
                    }
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        GeneratedBlock member = result.blocks().get(0);
        Assertions.assertEquals(GeneratedBlock.Kind.MEMBER, member.kind());
        Assertions.assertEquals(3, member.fromLine());
        Assertions.assertEquals(6, member.toLine());
        Assertions.assertFalse(result.isGenerated(9),
                "the hand-written method below must not be claimed by the marker above it");
    }

    @Test
    @DisplayName("a block marker spans a statement whose braces carry the boundary — one marker, not one per case")
    void aSwitchIsOneBlock() {
        // The case DEC-035 names. A generator that marked each arm would add a line per route forever, and
        // this test is what keeps the parser agreeing that the braces are enough.
        String source = """
                package p;
                class Router {
                    void route(String name) {
                        // @generated block %s
                        switch (name) {
                            case "a" -> handleA();
                            case "b" -> handleB();
                            case "c" -> {
                                nested();
                            }
                            default -> handleDefault();
                        }
                    }
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertEquals(1, result.blocks().size(), "one marker for the whole dispatch");
        GeneratedBlock block = result.blocks().get(0);
        Assertions.assertEquals(GeneratedBlock.Kind.BLOCK, block.kind());
        Assertions.assertEquals(4, block.fromLine());
        Assertions.assertEquals(12, block.toLine(), "including the nested braces inside the arms");
        Assertions.assertFalse(result.hasIssues());
    }

    @Test
    @DisplayName("a region pair spans both markers and everything between")
    void aRegionSpansItsPair() {
        String source = """
                package p;
                class C {
                    // @generated region begin routes %s
                    String route(String name) {
                        return switch (name) {
                            default -> "none";
                        };
                    }
                    // @generated region end routes
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertEquals(1, result.blocks().size());
        GeneratedBlock region = result.blocks().get(0);
        Assertions.assertEquals(GeneratedBlock.Kind.REGION, region.kind());
        Assertions.assertEquals("routes", region.regionId());
        Assertions.assertEquals(3, region.fromLine(), "the begin marker");
        Assertions.assertEquals(9, region.toLine(), "through the end marker");
        Assertions.assertFalse(result.hasIssues());
    }

    @Test
    @DisplayName("a region id is what pairs two markers, so two regions do not cross")
    void regionIdsPairTheHalves() {
        String source = """
                class C {
                    // @generated region begin first %s
                    void a() {}
                    // @generated region begin second %s
                    void b() {}
                    // @generated region end first
                    void c() {}
                    // @generated region end second
                }
                """.formatted(GEN, GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertEquals(2, result.blocks().size());
        GeneratedBlock first = result.blocks().stream()
                .filter(b -> "first".equals(b.regionId())).findFirst().orElseThrow();
        GeneratedBlock second = result.blocks().stream()
                .filter(b -> "second".equals(b.regionId())).findFirst().orElseThrow();
        Assertions.assertEquals(2, first.fromLine());
        Assertions.assertEquals(6, first.toLine(), "paired by id, not by order of appearance");
        Assertions.assertEquals(4, second.fromLine());
        Assertions.assertEquals(8, second.toLine());
    }

    // ---- extent, when the Java around it is awkward ----

    @Test
    @DisplayName("a brace inside a string literal does not end the block early")
    void bracesInStringsAreNotBraces() {
        // The bug a brace counter cannot avoid: `"}"` would close the block on the line it appears, and the
        // parser would report a span that is too short with nothing to signal it.
        String source = """
                class C {
                    // @generated block %s
                    String m() {
                        return "}";
                    }
                }
                """.formatted(GEN);

        GeneratedBlock block = GeneratedCodeParser.parse(source).blocks().get(0);

        Assertions.assertEquals(5, block.toLine(), "the real closing brace, not the one in the string");
    }

    @Test
    @DisplayName("an opening brace inside a string does not extend the block")
    void openBracesInStringsAreNotBraces() {
        String source = """
                class C {
                    // @generated block %s
                    String m() {
                        return "{";
                    }
                }
                """.formatted(GEN);

        GeneratedBlock block = GeneratedCodeParser.parse(source).blocks().get(0);

        Assertions.assertEquals(5, block.toLine());
    }

    @Test
    @DisplayName("a brace inside a text block does not end the block early")
    void bracesInTextBlocksAreNotBraces() {
        String source = "class C {\n"
                + "    // @generated block " + GEN + "\n"
                + "    String m() {\n"
                + "        return \"\"\"\n"
                + "            }\n"
                + "            {\n"
                + "            \"\"\";\n"
                + "    }\n"
                + "}\n";

        GeneratedBlock block = GeneratedCodeParser.parse(source).blocks().get(0);

        Assertions.assertEquals(8, block.toLine(),
                "the text block's braces are text, so the method's own brace closes it");
    }

    @Test
    @DisplayName("a brace inside a comment does not end the block early")
    void bracesInCommentsAreNotBraces() {
        String source = """
                class C {
                    // @generated block %s
                    String m() {
                        // }
                        /* } */
                        return "ok";
                    }
                }
                """.formatted(GEN);

        GeneratedBlock block = GeneratedCodeParser.parse(source).blocks().get(0);

        Assertions.assertEquals(7, block.toLine());
    }

    @Test
    @DisplayName("an escaped quote at the end of a string does not desynchronise the lexer")
    void escapedQuotesDoNotDesynchronise() {
        // `"\\"` then a real `"}"`: the naive reader sees the escaped quote as the terminator and then
        // reads the rest of the file as if it were code — the failure mode that silently reports a wrong
        // span for every following marker too.
        String source = "class C {\n"
                + "    // @generated block " + GEN + "\n"
                + "    String m() {\n"
                + "        String s = \"\\\\\";\n"
                + "        return \"}\";\n"
                + "    }\n"
                + "    // @generated block " + GEN + "\n"
                + "    void n() {\n"
                + "    }\n"
                + "}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertEquals(2, result.blocks().size());
        Assertions.assertEquals(6, result.blocks().get(0).toLine());
        Assertions.assertEquals(9, result.blocks().get(1).toLine(),
                "the second marker is still found, so the lexer resynchronised");
    }

    @Test
    @DisplayName("nested and anonymous classes inside a member do not end it early")
    void nestedTypesAreInsideTheSpan() {
        String source = """
                class C {
                    // @generated member %s
                    Runnable m() {
                        return new Runnable() {
                            @Override
                            public void run() {
                                inner();
                            }
                        };
                    }
                }
                """.formatted(GEN);

        GeneratedBlock block = GeneratedCodeParser.parse(source).blocks().get(0);

        Assertions.assertEquals(10, block.toLine(), "the anonymous class is inside the member");
    }

    @Test
    @DisplayName("an array initialiser below a block marker is a braced construct, and bounded by it")
    void anArrayInitialiserHasBraces() {
        String source = """
                class C {
                    // @generated member %s
                    static final int[] VALUES = {
                        1, 2, 3
                    };
                }
                """.formatted(GEN);

        GeneratedBlock block = GeneratedCodeParser.parse(source).blocks().get(0);

        Assertions.assertEquals(5, block.toLine());
    }

    @Test
    @DisplayName("a marker before a declaration with no braces is reported, not guessed at")
    void aFieldDeclarationHasNoBlockToBound() {
        // DEC-035 requires the annotated construct to have braces. Given `int value = 1;` there is nothing
        // to bound the span with, and extending it to the next blank line would invent a boundary.
        String source = """
                class C {
                    // @generated block %s
                    private int value = 1;
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "C.java");

        Assertions.assertTrue(result.blocks().isEmpty(), "no span can be claimed");
        Assertions.assertEquals(1, result.issues().size());
        Assertions.assertEquals(GeneratedCodeParser.MarkerIssue.Kind.MARKER_WITHOUT_BLOCK,
                result.issues().get(0).kind());
        Assertions.assertTrue(result.issues().get(0).describe("C.java").contains("C.java:2"),
                result.issues().get(0).describe("C.java"));
    }

    @Test
    @DisplayName("a marker at end of file is reported rather than extended to the file's end")
    void aTrailingMarkerIsReported() {
        String source = "class C {\n    void m() {\n    }\n    // @generated block " + GEN + "\n}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertTrue(result.blocks().isEmpty());
        Assertions.assertEquals(GeneratedCodeParser.MarkerIssue.Kind.MARKER_WITHOUT_BLOCK,
                result.issues().get(0).kind());
    }

    // ---- the rule DEC-035 exists for ----

    @Test
    @DisplayName("an unknown marker is recognised as a marker, and refused — never treated as hand-written")
    void anUnknownMarkerIsReportedNotIgnored() {
        // The behaviour that must not happen: reporting no generated code at all, so a tool edits code the
        // generator owns and the edit is lost on the next pass.
        String source = """
                class C {
                    // @generated patchwork something-new
                    void m() {
                    }
                }
                """;

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "C.java");

        Assertions.assertTrue(result.hasIssues());
        Assertions.assertTrue(result.hasUnknownMarkers(),
                "a caller about to edit must be able to tell this apart from a clean file");
        GeneratedCodeParser.MarkerIssue issue = result.issues().get(0);
        Assertions.assertEquals(GeneratedCodeParser.MarkerIssue.Kind.UNKNOWN_MARKER, issue.kind());
        Assertions.assertEquals(2, issue.line());
        Assertions.assertTrue(issue.describe("C.java").contains("kind=unknown_marker"),
                issue.describe("C.java"));
        Assertions.assertTrue(issue.describe("C.java").contains("do not treat"),
                "the report says what the caller must not do: " + issue.describe("C.java"));
    }

    @Test
    @DisplayName("a bare @generated is refused, because it claims code with no rule to apply")
    void aBareKeywordIsRefused() {
        String source = "class C {\n    // @generated\n    void m() {\n    }\n}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertTrue(result.blocks().isEmpty());
        Assertions.assertTrue(result.hasUnknownMarkers());
    }

    @Test
    @DisplayName("a file with no markers has no generated code and no issues")
    void aHandWrittenFileIsQuiet() {
        String source = """
                package p;
                public class Hand {
                    private int x;
                    void m() {
                        x = 1;
                    }
                }
                """;

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "Hand.java");

        Assertions.assertFalse(result.hasGeneratedCode());
        Assertions.assertFalse(result.hasIssues(),
                "a hand-written file is not an error: there is simply nothing generated in it");
        Assertions.assertEquals(8, result.lines());
    }

    @Test
    @DisplayName("prose about markers is not a marker, so documentation is not misread as generated code")
    void proseIsNotAMarker() {
        // The anchor is what keeps a document about markers — including DEC-035 — from parsing as a
        // marked-up file.
        String source = """
                class C {
                    /** A marker begins with @generated, as in @generated file x. */
                    // NOTE: @generated file something
                    void m() {
                    }
                }
                """;

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertFalse(result.hasGeneratedCode());
        Assertions.assertFalse(result.hasIssues());
    }

    @Test
    @DisplayName("an unclosed region is reported rather than extended to the end of the file")
    void anUnclosedRegionIsReported() {
        String source = """
                class C {
                    // @generated region begin routes %s
                    void m() {
                    }
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "C.java");

        Assertions.assertTrue(result.blocks().isEmpty(),
                "extending it would silently claim the rest of the file");
        Assertions.assertEquals(GeneratedCodeParser.MarkerIssue.Kind.UNCLOSED_REGION,
                result.issues().get(0).kind());
        Assertions.assertEquals(2, result.issues().get(0).line());
    }

    @Test
    @DisplayName("a region end with no begin is reported")
    void anUnmatchedRegionEndIsReported() {
        String source = "class C {\n    // @generated region end routes\n}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertEquals(GeneratedCodeParser.MarkerIssue.Kind.UNMATCHED_REGION_END,
                result.issues().get(0).kind());
    }

    @Test
    @DisplayName("an unclosed brace is reported rather than extended")
    void anUnclosedBraceIsReported() {
        String source = "class C {\n    // @generated member " + GEN + "\n    void m() {\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "C.java");

        Assertions.assertEquals(GeneratedCodeParser.MarkerIssue.Kind.UNCLOSED_BLOCK,
                result.issues().get(0).kind());
        Assertions.assertTrue(result.blocks().isEmpty());
    }

    // ---- what a consumer asks about a line ----

    @Test
    @DisplayName("the innermost block answers for a line, which is the useful answer when spans nest")
    void theInnermostBlockWins() {
        // A file marker contains a member marker. A caller asking "may I edit line 12" needs the most
        // specific answer, not the file's.
        String source = GeneratedCodeMarkers.fileHeader(GEN, "desc")
                + "package p;\n"
                + "class C {\n"
                + "    // @generated member " + GEN + "\n"
                + "    void m() {\n"
                + "    }\n"
                + "}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source);

        Assertions.assertEquals(2, result.blocks().size());
        GeneratedBlock innermost = result.innermostBlockAt(6).orElseThrow();
        Assertions.assertEquals(GeneratedBlock.Kind.MEMBER, innermost.kind());
        Assertions.assertEquals(GeneratedBlock.Kind.FILE, result.blockAt(6).orElseThrow().kind(),
                "blockAt returns the first match in order, which is the file");
        Assertions.assertEquals(5, innermost.fromLine(), "the member marker's own line");
        Assertions.assertEquals(7, innermost.toLine());
    }

    @Test
    @DisplayName("line endings do not shift the spans")
    void crlfAndCrAreHandled() {
        String lf = "class C {\n    // @generated block " + GEN + "\n    void m() {\n    }\n}\n";
        String crlf = lf.replace("\n", "\r\n");

        GeneratedBlock onLf = GeneratedCodeParser.parse(lf).blocks().get(0);
        GeneratedBlock onCrlf = GeneratedCodeParser.parse(crlf).blocks().get(0);

        Assertions.assertEquals(onLf.fromLine(), onCrlf.fromLine());
        Assertions.assertEquals(onLf.toLine(), onCrlf.toLine());
        Assertions.assertEquals(4, onCrlf.toLine());
    }

    @Test
    @DisplayName("an empty or null source parses to nothing rather than throwing")
    void emptySourceIsSafe() {
        Assertions.assertEquals(0, GeneratedCodeParser.parse("").lines());
        Assertions.assertEquals(0, GeneratedCodeParser.parse(null).lines());
        Assertions.assertFalse(GeneratedCodeParser.parse("").hasIssues());
    }

    @Test
    @DisplayName("the generator name is recoverable from a marker, and only when it is there")
    void theGeneratorNameIsAdvisory() {
        String source = GeneratedCodeMarkers.fileHeader(GEN, "desc") + "package p;\n";

        GeneratedCodeMarkers.Found marker =
                GeneratedCodeMarkers.recognise(1, source.split("\n")[0]).orElseThrow();

        Assertions.assertEquals(GEN, marker.generator().orElseThrow(),
                "the description after the separator is not part of the name");
        Assertions.assertEquals(GEN, GeneratedCodeParser.parse(source).blocks().get(0).generator());

        // A marker with no payload still parses; the name is advisory, never required.
        Assertions.assertTrue(GeneratedCodeMarkers.recognise(1, "// @generated file").orElseThrow()
                .generator().isEmpty());
    }

    @Test
    @DisplayName("a region's generator is recoverable past the id")
    void aRegionMarkerCarriesItsGenerator() {
        String line = GeneratedCodeMarkers.regionBegin("routes", GEN);

        GeneratedCodeMarkers.Found marker = GeneratedCodeMarkers.recognise(1, line).orElseThrow();

        Assertions.assertEquals("routes", marker.regionId().orElseThrow());
        Assertions.assertEquals(GEN, marker.generator().orElseThrow(),
                "the id must not be mistaken for the generator name");
    }

    @Test
    @DisplayName("blocks are reported in file order, so a listing reads top to bottom")
    void blocksComeBackInOrder() {
        String source = """
                class C {
                    // @generated member %s
                    void a() {
                    }
                    // @generated member %s
                    void b() {
                    }
                }
                """.formatted(GEN, GEN);

        List<GeneratedBlock> blocks = GeneratedCodeParser.parse(source).blocks();

        Assertions.assertEquals(2, blocks.size());
        Assertions.assertTrue(blocks.get(0).fromLine() < blocks.get(1).fromLine());
        Assertions.assertEquals(List.of(2, 5),
                blocks.stream().map(GeneratedBlock::fromLine).toList());
    }

    @Test
    @DisplayName("a block reports a closed state, and an issue is how a caller sees it is not")
    void closedStateIsExplicit() {
        String good = "class C {\n    // @generated member " + GEN + "\n    void m() {\n    }\n}\n";

        GeneratedBlock block = GeneratedCodeParser.parse(good).blocks().get(0);

        Assertions.assertTrue(block.closed());
        Assertions.assertTrue(block.describe("C.java").contains("C.java:2-4"),
                block.describe("C.java"));
        Optional<GeneratedBlock> at = GeneratedCodeParser.parse(good).blockAt(3);
        Assertions.assertTrue(at.isPresent());
    }

    // ---- end to end, on the shape a real generator emits ----

    @Test
    @DisplayName("a wholly-generated file with a hand-written nested type reads as fully generated")
    void aRealGeneratedFileShape() {
        // The shape hipster-entity actually commits: the two-line header, then a class, then a nested type
        // a developer added by hand. The file marker claims all of it — including the hand-written part,
        // which is exactly what a caller needs to be told, because that edit IS overwritten by a forced
        // regeneration.
        String source = GeneratedCodeMarkers.fileHeader(GEN, "Tracking builder for the Order view.")
                + "package p;\n"
                + "\n"
                + "import java.util.List;\n"
                + "\n"
                + "public class OrderBuilder {\n"
                + "\n"
                + "    private String status;\n"
                + "\n"
                + "    public String status() {\n"
                + "        return status;\n"
                + "    }\n"
                + "\n"
                + "    /**\n"
                + "     * A user-authored variant. The generator never emits it and never deletes it.\n"
                + "     */\n"
                + "    public static final class Strict extends OrderBuilder {\n"
                + "        public Strict(String s) { status = s; }\n"
                + "    }\n"
                + "}\n";

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "OrderBuilder.java");

        Assertions.assertTrue(result.isWhollyGenerated());
        Assertions.assertFalse(result.hasIssues(),
                "a real generated file must parse cleanly: " + result.issues());
        Assertions.assertEquals(1, result.blocks().size());
        Assertions.assertEquals(result.lines(), result.blocks().get(0).toLine());
        Assertions.assertTrue(result.isGenerated(20), "the hand-written nested type is inside the file span");
        Assertions.assertEquals(GeneratedBlock.Kind.FILE, result.innermostBlockAt(20).orElseThrow().kind(),
                "and there is no more specific block to prefer");
    }

    @Test
    @DisplayName("a partly-generated file names only the generated member, and leaves the rest alone")
    void aPartlyGeneratedFileNamesOnlyItsMember() {
        // The case markers 2-4 exist for, and the one a file marker cannot express: the generator owns one
        // member of a file a developer otherwise wrote.
        String source = """
                package p;

                public class OrderService {

                    // @generated member %s
                    public void dispatch(String name) {
                        switch (name) {
                            case "a" -> handleA();
                            default -> handleDefault();
                        }
                    }

                    public void handWritten() {
                        dispatch("a");
                    }
                }
                """.formatted(GEN);

        GeneratedCodeParser.Result result = GeneratedCodeParser.parse(source, "OrderService.java");

        Assertions.assertFalse(result.isWhollyGenerated());
        Assertions.assertFalse(result.hasIssues());
        Assertions.assertEquals(1, result.blocks().size());
        GeneratedBlock generated = result.blocks().get(0);
        Assertions.assertEquals(GeneratedBlock.Kind.MEMBER, generated.kind());
        Assertions.assertEquals(5, generated.fromLine());
        Assertions.assertEquals(11, generated.toLine(), "the nested switch is inside the member");
        Assertions.assertEquals(GEN, generated.generator());

        // The lines a caller must not hand-edit, and the ones it may.
        for (int line = 5; line <= 11; line++) {
            Assertions.assertTrue(result.isGenerated(line), "line " + line + " is generated");
        }
        Assertions.assertFalse(result.isGenerated(4), "the blank line before it is not");
        Assertions.assertFalse(result.isGenerated(12), "the blank line after the brace is not");
        Assertions.assertFalse(result.isGenerated(14), "the hand-written method below is not");
    }
}
