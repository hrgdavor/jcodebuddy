// {@link com.codebuddy.merge.ConflictMarkerParserTest} Tests parsing of git conflict markers into blocks and whole versions.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser is the tool's eyes: a block it misreads becomes a wrong
 * resolution or, worse, a false clean. These tests pin the block extraction,
 * the whole-version reconstruction and the loud failures on corrupt markers.
 */
class ConflictMarkerParserTest {

    private static final String MARKED = """
            package com.example.demo;

            import java.util.List;
            <<<<<<< ours
            import java.math.BigDecimal;
            =======
            import java.time.Instant;
            >>>>>>> theirs

            public class OrderService {
            }
            """;

    @Test
    @DisplayName("a merge-style block yields both sides, labels and exact line numbers")
    void parsesASingleBlock() {
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(MARKED);

        assertTrue(parsed.hasConflicts());
        assertEquals(1, parsed.blocks().size());

        ConflictMarkerParser.Block block = parsed.blocks().get(0);
        assertEquals(1, block.number());
        assertEquals(4, block.startLine());
        assertEquals(8, block.endLine());
        assertEquals("ours", block.oursLabel());
        assertEquals("theirs", block.theirsLabel());
        assertNull(block.baseLabel());
        assertFalse(block.hasBase());
        assertEquals("import java.math.BigDecimal;", block.ours());
        assertEquals("import java.time.Instant;", block.theirs());
        assertEquals("""
                <<<<<<< ours
                import java.math.BigDecimal;
                =======
                import java.time.Instant;
                >>>>>>> theirs""", block.raw());
        assertEquals(new Region(4, 8), block.markerRegion());
    }

    @Test
    @DisplayName("the whole versions are the file with every block replaced by one side")
    void reconstructsWholeVersions() {
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(MARKED);

        assertEquals("""
                package com.example.demo;

                import java.util.List;
                import java.math.BigDecimal;

                public class OrderService {
                }
                """, parsed.oursVersion());
        assertEquals("""
                package com.example.demo;

                import java.util.List;
                import java.time.Instant;

                public class OrderService {
                }
                """, parsed.theirsVersion());
        assertEquals(Optional.empty(), parsed.baseVersion(),
            "a merge-style file carries no base, and none may be fabricated");
    }

    @Test
    @DisplayName("a diff3 block carries its base side, and the base version is reconstructed")
    void parsesDiff3BaseSection() {
        String marked = """
                <<<<<<< ours
                package com.example.ours;
                ||||||| base
                package com.example.shared;
                =======
                package com.example.theirs;
                >>>>>>> theirs
                """;
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(marked);

        assertEquals(1, parsed.blocks().size());
        ConflictMarkerParser.Block block = parsed.blocks().get(0);
        assertTrue(block.hasBase());
        assertEquals("base", block.baseLabel());
        assertEquals("package com.example.ours;", block.ours());
        assertEquals("package com.example.shared;", block.base());
        assertEquals("package com.example.theirs;", block.theirs());

        assertEquals("package com.example.ours;\n", parsed.oursVersion());
        assertEquals("package com.example.shared;\n",
            parsed.baseVersion().orElseThrow());
    }

    @Test
    @DisplayName("the base version exists only when every block carries a base side")
    void baseVersionRequiresEveryBlockToCarryABase() {
        String marked = """
                <<<<<<< ours
                int a = 1;
                ||||||| base
                int a = 0;
                =======
                int a = 2;
                >>>>>>> theirs
                <<<<<<< ours
                int b = 1;
                =======
                int b = 2;
                >>>>>>> theirs
                """;
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(marked);

        assertEquals(2, parsed.blocks().size());
        assertTrue(parsed.blocks().get(0).hasBase());
        assertFalse(parsed.blocks().get(1).hasBase());
        assertEquals(Optional.empty(), parsed.baseVersion());
    }

    @Test
    @DisplayName("multiple blocks are numbered in file order and reconstructed independently")
    void parsesMultipleBlocks() {
        String marked = """
                <<<<<<< HEAD
                first ours
                =======
                first theirs
                >>>>>>> 3f9a1c2 (their commit)
                clean line
                <<<<<<< HEAD
                second ours
                =======
                second theirs
                >>>>>>> 3f9a1c2 (their commit)
                """;
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(marked);

        assertEquals(2, parsed.blocks().size());
        assertEquals(1, parsed.blocks().get(0).number());
        assertEquals(2, parsed.blocks().get(1).number());
        assertEquals("HEAD", parsed.blocks().get(0).oursLabel());
        assertEquals("3f9a1c2 (their commit)", parsed.blocks().get(1).theirsLabel());
        assertEquals("first ours\nclean line\nsecond ours\n", parsed.oursVersion());
        assertEquals("first theirs\nclean line\nsecond theirs\n", parsed.theirsVersion());
    }

    @Test
    @DisplayName("an empty side is allowed - one branch deleted the region")
    void parsesEmptySides() {
        String marked = """
                <<<<<<< ours
                =======
                public void addedOnlyOnTheirSide() {
                }
                >>>>>>> theirs
                """;
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(marked);

        assertEquals("", parsed.blocks().get(0).ours());
        assertTrue(parsed.blocks().get(0).theirs().contains("addedOnlyOnTheirSide"));
        assertEquals("", parsed.oursVersion());
    }

    @Test
    @DisplayName("CRLF files keep their line endings through parsing and reconstruction")
    void preservesCarriageReturnEndings() {
        String crlf = MARKED.replace("\n", "\r\n");
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(crlf);

        assertEquals("\r\n", parsed.eol());
        assertTrue(parsed.endsWithNewline());
        assertEquals(crlf, parsed.conflictedText(), "round-trips the received bytes");
        assertEquals(1, parsed.blocks().size());
        assertEquals("import java.math.BigDecimal;", parsed.blocks().get(0).ours());
        assertTrue(parsed.oursVersion().contains("import java.math.BigDecimal;\r\n"));
    }

    @Test
    @DisplayName("a file without a trailing newline round-trips without gaining one")
    void preservesMissingTrailingNewline() {
        String marked = MARKED.substring(0, MARKED.length() - 1);
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(marked);

        assertFalse(parsed.endsWithNewline());
        assertEquals(marked, parsed.conflictedText());
    }

    @Test
    @DisplayName("a separator longer than seven equals signs is still a separator")
    void acceptsLongerSeparators() {
        String marked = """
                <<<<<<< ours
                int a = 1;
                =========
                int a = 2;
                >>>>>>> theirs
                """;
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(marked);

        assertEquals(1, parsed.blocks().size());
        assertEquals("int a = 1;", parsed.blocks().get(0).ours());
        assertEquals("int a = 2;", parsed.blocks().get(0).theirs());
    }

    @Test
    @DisplayName("a stray separator outside any block is ordinary content")
    void straySeparatorOutsideBlockIsContent() {
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse("""
                public class Banner {
                    String rule = "=======";
                }
                =======
                """);

        assertFalse(parsed.hasConflicts());
    }

    @Test
    @DisplayName("a file without markers parses as clean")
    void cleanFileHasNoBlocks() {
        ConflictMarkerParser.ParsedFile parsed = ConflictMarkerParser.parse(
            "package com.example.demo;\n\npublic class OrderService {\n}\n");

        assertFalse(parsed.hasConflicts());
        assertEquals(0, parsed.blocks().size());
    }

    @Test
    @DisplayName("an unterminated block fails loudly instead of reporting a false clean")
    void unterminatedBlockThrows() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ConflictMarkerParser.parse("""
                    <<<<<<< ours
                    int a = 1;
                    =======
                    int a = 2;
                    """));
        assertTrue(failure.getMessage().contains("unterminated"), failure.getMessage());
        assertTrue(failure.getMessage().contains("line 1"), failure.getMessage());
    }

    @Test
    @DisplayName("a nested start marker fails loudly")
    void nestedStartMarkerThrows() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ConflictMarkerParser.parse("""
                    <<<<<<< ours
                    int a = 1;
                    <<<<<<< inner
                    =======
                    int a = 2;
                    >>>>>>> theirs
                    """));
        assertTrue(failure.getMessage().contains("nested"), failure.getMessage());
        assertTrue(failure.getMessage().contains("line 3"), failure.getMessage());
    }

    @Test
    @DisplayName("a block without a separator fails loudly")
    void missingSeparatorThrows() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ConflictMarkerParser.parse("""
                    <<<<<<< ours
                    int a = 1;
                    >>>>>>> theirs
                    """));
        assertTrue(failure.getMessage().contains("separator"), failure.getMessage());
    }

    @Test
    @DisplayName("a duplicate separator fails loudly")
    void duplicateSeparatorThrows() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ConflictMarkerParser.parse("""
                    <<<<<<< ours
                    int a = 1;
                    =======
                    int a = 2;
                    =======
                    int a = 3;
                    >>>>>>> theirs
                    """));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    @DisplayName("a base section after the separator fails loudly")
    void misplacedBaseSectionThrows() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ConflictMarkerParser.parse("""
                    <<<<<<< ours
                    int a = 1;
                    =======
                    int a = 2;
                    ||||||| base
                    int a = 0;
                    >>>>>>> theirs
                    """));
        assertTrue(failure.getMessage().contains("misplaced"), failure.getMessage());
    }
}
