// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.builder;

import hr.hrg.watch2.core.TransformationResult;
import static org.junit.Assert.*;

import org.junit.Test;

public class RecordBuilderFormattingTest {

    @Test
    public void testOutputFormatting() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("    "); // 4 space indent
        String code = "@GenerateBuilder public record TestRecord(String name) {}";

        TransformationResult result = engine.generate("TestRecord.java", code, 1);

        assertNotNull(result);
        assertEquals(1, result.edits().size());

        String newCode = result.edits().get(0).newText();

        System.out.println("--- NORMAL ---");
        System.out.println(newCode);

        // 1. Check for @GenerateBuilder
        assertTrue("Should contain @GenerateBuilder annotation", newCode.contains("@GenerateBuilder"));

        // 2. Check for extra brackets
        int openBrackets = countOccurrences(newCode, "{");
        int closeBrackets = countOccurrences(newCode, "}");
        assertEquals("Brackets should be balanced. Found: " + newCode, openBrackets, closeBrackets);

        // 3. Check for indentation
        assertTrue("Builder class should be indented", newCode.contains("\n    public static class Builder"));
        assertTrue("Field should be double indented", newCode.contains("\n        private String name;"));
    }

    @Test
    public void testOutputFormattingNoSpaces() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("    ");
        String code = "@GenerateBuilder public record TestRecord(String name){}";

        TransformationResult result = engine.generate("TestRecord.java", code, 1);
        String newCode = result.edits().get(0).newText();

        System.out.println("--- NO SPACES ---");
        System.out.println(newCode);

        assertTrue("Should contain @GenerateBuilder", newCode.contains("@GenerateBuilder"));
        assertEquals("Brackets should be balanced", countOccurrences(newCode, "{"), countOccurrences(newCode, "}"));
    }

    @Test
    public void testNoDuplicateComment() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("    ");
        String code = "@GenerateBuilder public record TestRecord(String name) {\n}";

        TransformationResult result = engine.generate("TestRecord.java", code, 1);
        String newCode = result.edits().get(0).newText();

        int count = countOccurrences(newCode, "@GenerateBuilder");
        assertEquals("Should have exactly one @GenerateBuilder annotation", 1, count);
    }

    @Test
    public void testCodeAfterRecord() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("    ");
        String code = "@GenerateBuilder public record TestRecord(String name) {}\n\npublic class Other {}";

        TransformationResult result = engine.generate("TestRecord.java", code, 1);

        // Calculate the full code after application
        java.util.List<hr.hrg.watch2.core.CodeEdit> edits = result.edits();
        String applied = hr.hrg.watch2.core.CodeEditApplier.applyStyles(code, edits);

        System.out.println("--- CODE AFTER ---");
        System.out.println(applied);
        System.out.println("-----------------");

        int count = countOccurrences(applied, "}");
        assertEquals("Should have exactly 7 closing braces", 7, count);
    }

    @Test
    public void testNestedRecord() {
        BuilderTransformationEngine engine = new BuilderTransformationEngine("    ");
        String code = "public class Outer {\n    @GenerateBuilder public record Inner(String name) {}\n}";

        TransformationResult result = engine.generate("Test.java", code, 2);

        // Calculate the full code after application
        java.util.List<hr.hrg.watch2.core.CodeEdit> edits = result.edits();
        String applied = hr.hrg.watch2.core.CodeEditApplier.applyStyles(code, edits);

        System.out.println("--- NESTED ---");
        System.out.println(applied);
        System.out.println("-----------------");

        int count = countOccurrences(applied, "}");
        assertEquals("Should have exactly 7 closing braces", 7, count);
    }

    private int countOccurrences(String text, String target) {
        int count = 0;
        int lastIndex = 0;
        while ((lastIndex = text.indexOf(target, lastIndex)) != -1) {
            count++;
            lastIndex += target.length();
        }
        return count;
    }
}
