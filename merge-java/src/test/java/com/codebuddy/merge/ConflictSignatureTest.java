// {@link com.codebuddy.merge.ConflictSignatureTest} Tests for conflict identity.
// {enabled:true, blockMarker: "implicit"}
package com.codebuddy.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signature is what lets a recorded decision be replayed safely, so it must
 * be stable under irrelevant churn and different whenever the disagreement
 * actually differs.
 */
class ConflictSignatureTest {

    @Test
    @DisplayName("is stable under whitespace and formatting churn")
    void stableUnderFormattingChurn() {
        Conflict compact = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "same", "base", "int x=1;", "int y=2;");
        Conflict reformatted = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "same", "base", "  int   x = 1;  ", "int y = 2;");

        assertEquals(ConflictSignature.of(compact), ConflictSignature.of(reformatted),
            "reindenting a conflict must not invalidate a recorded decision");
    }

    @Test
    @DisplayName("ignores blank lines")
    void ignoresBlankLines() {
        Conflict sparse = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "same", "base", "int x = 1;", "int y = 2;");
        Conflict padded = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "same", "base", "\n\nint x = 1;\n\n", "\nint y = 2;\n");

        assertEquals(ConflictSignature.of(sparse), ConflictSignature.of(padded));
    }

    @Test
    @DisplayName("differs when the conflict type differs")
    void differsByType() {
        Conflict imports = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "d", "base", "int x = 1;", "int y = 2;");
        Conflict constants = new Conflict(ConflictType.CONSTANT_ADD, "A.java",
            "d", "base", "int x = 1;", "int y = 2;");

        assertNotEquals(ConflictSignature.of(imports), ConflictSignature.of(constants));
    }

    @Test
    @DisplayName("differs when the file differs")
    void differsByFile() {
        Conflict inA = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "d", "base", "int x = 1;", "int y = 2;");
        Conflict inB = new Conflict(ConflictType.IMPORT_ADD, "B.java",
            "d", "base", "int x = 1;", "int y = 2;");

        assertNotEquals(ConflictSignature.of(inA), ConflictSignature.of(inB),
            "decisions must not leak between files");
    }

    @Test
    @DisplayName("differs when the disagreement differs")
    void differsByContent() {
        Conflict one = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "d", "base", "import a.A;", "import b.B;");
        Conflict two = new Conflict(ConflictType.IMPORT_ADD, "A.java",
            "d", "base", "import c.C;", "import d.D;");

        assertNotEquals(ConflictSignature.of(one), ConflictSignature.of(two));
    }

    @Test
    @DisplayName("normalises Windows separators in the file path")
    void normalisesPathSeparators() {
        Conflict windows = new Conflict(ConflictType.IMPORT_ADD, "src\\main\\A.java",
            "d", "base", "int x = 1;", "int y = 2;");

        ConflictSignature signature = ConflictSignature.of(windows);

        assertEquals("src/main/A.java", signature.filePath());
        assertFalse(signature.filePath().contains("\\"));
    }

    @Test
    @DisplayName("produces a filesystem-safe file name")
    void producesSafeFileName() {
        ConflictSignature signature =
            ConflictSignature.of(ConflictFixtures.sample(ConflictType.IMPORT_ADD));

        String fileName = signature.toFileName();
        assertTrue(fileName.startsWith("import_add-"), "was " + fileName);
        assertTrue(fileName.matches("[a-z_]+-[0-9a-f]+"),
            "the name must be usable as a file name: " + fileName);
    }

    @Test
    @DisplayName("handles missing content without failing")
    void handlesMissingContent() {
        ConflictSignature signature = ConflictSignature.of(
            new Conflict(ConflictType.IMPORT_ADD, null, null, null, null, null));

        assertTrue(signature.contentHash().length() > 0);
        assertEquals("<unknown>", signature.filePath());
    }

    @Test
    @DisplayName("normalisation strips indentation, collapses spacing and drops blank lines")
    void normalisesContent() {
        assertEquals("int x=1;\nreturn x;\n",
            ConflictSignature.normalise("  int x = 1;  \n\n    return   x;\n"));
    }
}
