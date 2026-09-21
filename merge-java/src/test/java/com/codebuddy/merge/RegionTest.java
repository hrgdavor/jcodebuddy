// {@link com.codebuddy.merge.RegionTest} Tests for line-range attribution.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regions are what let a mixed file be partly resolved: they are the evidence
 * that an automatic change and a manual one do not touch the same lines.
 */
class RegionTest {

    private static final String BASE = """
        import java.util.List;
        import java.util.Map;
        class Payment {
            void process() {
                audit();
            }
            void refund() {
            }
        }
        """;

    @Test
    @DisplayName("locates a declaration in the base version")
    void locatesDeclaration() {
        Region region = Region.of(BASE, "import java.util.List;");

        assertTrue(region.isKnown());
        assertEquals(1, region.startLine());
        assertEquals(1, region.endLine());
    }

    @Test
    @DisplayName("locates a multi-line region")
    void locatesMultiLineRegion() {
        Region region = Region.of(BASE, "void process() {\n    audit();\n}");

        assertTrue(region.isKnown());
        assertEquals(4, region.startLine());
        assertTrue(region.endLine() >= 6, "region was " + region);
    }

    @Test
    @DisplayName("matches despite whitespace differences")
    void matchesDespiteWhitespace() {
        Region tight = Region.of(BASE, "void process(){audit();}");
        Region loose = Region.of(BASE, "   void   process()   {\n      audit();\n   }");

        assertTrue(tight.isKnown(), "whitespace-insensitive matching should succeed");
        assertEquals(tight, loose);
    }

    @Test
    @DisplayName("reports an unknown region when the anchor is absent")
    void reportsUnknownWhenAbsent() {
        Region region = Region.of(BASE, "not in this file at all");

        assertFalse(region.isKnown());
        assertEquals(Region.unknown(), region);
    }

    @Test
    @DisplayName("treats blank and null anchors as unknown rather than matching everything")
    void treatsBlankAnchorAsUnknown() {
        assertFalse(Region.of(BASE, "").isKnown());
        assertFalse(Region.of(BASE, "   ").isKnown());
        assertFalse(Region.of(BASE, null).isKnown());
        assertFalse(Region.of(null, "x").isKnown());
    }

    @Test
    @DisplayName("detects overlap")
    void detectsOverlap() {
        Region first = Region.spanning(1, 3);
        Region second = Region.spanning(3, 5);
        Region third = Region.spanning(4, 6);

        assertTrue(first.overlaps(second), "sharing line 3 is an overlap");
        assertTrue(second.overlaps(first), "overlap is symmetric");
        assertTrue(first.overlaps(first));
        assertFalse(first.overlaps(third), "lines 1-3 and 4-6 are disjoint");
        assertFalse(third.overlaps(first));
    }

    @Test
    @DisplayName("never reports overlap with an unknown region")
    void unknownNeverOverlaps() {
        Region known = Region.spanning(1, 100);

        assertFalse(Region.unknown().overlaps(known),
            "overlaps() is the line-arithmetic question; callers must check isKnown()");
        assertFalse(known.overlaps(Region.unknown()));
        assertFalse(Region.unknown().overlaps(Region.unknown()));
    }

    @Test
    @DisplayName("rejects a region that ends before it starts")
    void rejectsInvertedRegion() {
        assertThrows(IllegalArgumentException.class, () -> new Region(5, 2));
    }

    @Test
    @DisplayName("normalises an inverted span instead of failing")
    void normalisesInvertedSpan() {
        assertEquals(new Region(2, 5), Region.spanning(5, 2));
    }

    @Test
    @DisplayName("counts its lines")
    void countsLines() {
        assertEquals(3, Region.spanning(2, 4).lineCount());
        assertEquals(1, Region.line(7).lineCount());
        assertEquals(0, Region.unknown().lineCount());
    }

    @Test
    @DisplayName("finds the offset span of a needle")
    void findsOffsetSpan() {
        Optional<int[]> span = Region.find("abc DEF ghi", "DEF");

        assertTrue(span.isPresent());
        assertEquals(4, span.get()[0]);
        assertEquals(7, span.get()[1]);
    }

    @Test
    @DisplayName("computes 1-based line numbers")
    void computesLineNumbers() {
        assertEquals(1, Region.lineOf("a\nb", 0));
        assertEquals(2, Region.lineOf("a\nb", 2));
        assertEquals(3, Region.lineOf("a\nb\nc", 4));
    }

    @Test
    @DisplayName("reports a conflict against another, treating unknown as overlapping")
    void conflictOverlapIsConservative() {
        Conflict early = ConflictFixtures.sample(ConflictType.IMPORT_ADD)
            .withRegion(Region.spanning(1, 2));
        Conflict late = ConflictFixtures.sample(ConflictType.COMMENT_ADD)
            .withRegion(Region.spanning(40, 50));
        Conflict unknown = ConflictFixtures.sample(ConflictType.STRUCTURAL_CHANGE);

        assertFalse(early.conflictsWith(late), "disjoint regions may be applied separately");
        assertTrue(early.conflictsWith(unknown),
            "an unattributable conflict must be assumed to overlap, never assumed distinct");
        assertTrue(unknown.conflictsWith(unknown));
        assertFalse(early.conflictsWith(null));
    }
}
